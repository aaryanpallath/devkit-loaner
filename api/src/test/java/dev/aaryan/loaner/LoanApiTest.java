package dev.aaryan.loaner;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import dev.aaryan.loaner.device.Device;
import dev.aaryan.loaner.device.Reservation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanApiTest {

    static final Instant NOW = Instant.parse("2027-06-15T12:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @DynamicPropertySource
    static void mongo(DynamicPropertyRegistry r) {
        r.add("spring.mongodb.uri", () -> EmbeddedMongo.uri("loaner-test"));
    }

    @LocalServerPort int port;
    @Autowired MongoTemplate mongo;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), Device.class);
    }

    // ------------------------------------------------------------------ helpers
    record Resp(int status, JsonNode body) {}

    Resp call(String method, String path, String json) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        b = json == null ? b.method(method, HttpRequest.BodyPublishers.noBody())
                : b.method(method, HttpRequest.BodyPublishers.ofString(json));
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return new Resp(r.statusCode(), r.body().isBlank() ? null : JSON.readTree(r.body()));
    }

    String device(String tag) throws Exception {
        Resp r = call("POST", "/api/devices",
                "{\"assetTag\":\"" + tag + "\",\"name\":\"Dev kit " + tag + "\",\"kind\":\"DEV_KIT\",\"location\":\"Redmond B4\"}");
        assertThat(r.status()).isEqualTo(201);
        return r.body().get("id").asString();
    }

    static String booking(String who, String start, String end) {
        return "{\"borrower\":\"" + who + "\",\"start\":\"" + start + "\",\"end\":\"" + end + "\"}";
    }

    Resp reserve(String id, String start, String end) throws Exception {
        return call("POST", "/api/devices/" + id + "/reservations", booking("ana@example.com", start, end));
    }

    // ------------------------------------------------------------------ tests
    @Test
    void lifecycle_book_checkout_return() throws Exception {
        String id = device("DK-001");
        Resp r = reserve(id, "2027-06-16T09:00:00Z", "2027-06-18T17:00:00Z");
        assertThat(r.status()).isEqualTo(201);
        String rid = r.body().get("id").asString();
        assertThat(call("POST", "/api/devices/" + id + "/reservations/" + rid + "/checkout", null).body()
                .get("state").asString()).isEqualTo("CHECKED_OUT");
        Resp back = call("POST", "/api/devices/" + id + "/reservations/" + rid + "/return", null);
        assertThat(back.body().get("state").asString()).isEqualTo("RETURNED");
        assertThat(back.body().get("returnedAt").asString()).isEqualTo(NOW.toString());
    }

    @Test
    void overlapping_booking_is_rejected_but_adjacent_and_freed_slots_are_not() throws Exception {
        String id = device("DK-002");
        Resp first = reserve(id, "2027-06-16T09:00:00Z", "2027-06-17T09:00:00Z");
        assertThat(reserve(id, "2027-06-16T20:00:00Z", "2027-06-18T09:00:00Z").status()).isEqualTo(409);
        assertThat(reserve(id, "2027-06-17T09:00:00Z", "2027-06-18T09:00:00Z").status()).isEqualTo(201);  // adjacent
        call("DELETE", "/api/devices/" + id + "/reservations/" + first.body().get("id").asString(), null);
        assertThat(reserve(id, "2027-06-16T10:00:00Z", "2027-06-16T12:00:00Z").status()).isEqualTo(201);  // freed
    }

    @Test
    void invalid_requests_are_rejected() throws Exception {
        String id = device("DK-003");
        assertThat(reserve(id, "2027-06-18T09:00:00Z", "2027-06-17T09:00:00Z").status()).isEqualTo(400);
        assertThat(reserve(id, "2027-06-16T09:00:00Z", "2027-07-16T09:00:00Z").status()).isEqualTo(400);  // > 14 days
        assertThat(reserve(id, "2027-06-01T09:00:00Z", "2027-06-02T09:00:00Z").status()).isEqualTo(400);  // ended
        assertThat(call("POST", "/api/devices/" + id + "/reservations",
                booking("not-an-email", "2027-06-16T09:00:00Z", "2027-06-17T09:00:00Z")).status()).isEqualTo(400);
        assertThat(reserve("nope", "2027-06-16T09:00:00Z", "2027-06-17T09:00:00Z").status()).isEqualTo(404);
        assertThat(call("POST", "/api/devices",
                "{\"assetTag\":\"DK-003\",\"name\":\"x\",\"kind\":\"CONSOLE\",\"location\":\"x\"}").status()).isEqualTo(409);
    }

    @Test
    void illegal_state_changes_are_rejected() throws Exception {
        String id = device("DK-004");
        String rid = reserve(id, "2027-06-16T09:00:00Z", "2027-06-17T09:00:00Z").body().get("id").asString();
        assertThat(call("POST", "/api/devices/" + id + "/reservations/" + rid + "/return", null).status()).isEqualTo(409);
        call("POST", "/api/devices/" + id + "/reservations/" + rid + "/checkout", null);
        assertThat(call("POST", "/api/devices/" + id + "/reservations/" + rid + "/checkout", null).status()).isEqualTo(409);
        assertThat(call("DELETE", "/api/devices/" + id + "/reservations/" + rid, null).status()).isEqualTo(409);
    }

    @Test
    void availability_filter_excludes_booked_devices() throws Exception {
        String busy = device("DK-005");
        String free = device("DK-006");
        reserve(busy, "2027-06-20T09:00:00Z", "2027-06-22T09:00:00Z");
        Resp r = call("GET", "/api/devices?from=2027-06-21T00:00:00Z&to=2027-06-21T12:00:00Z", null);
        List<String> ids = new ArrayList<>();
        r.body().forEach(n -> ids.add(n.get("id").asString()));
        assertThat(ids).containsExactly(free);
    }

    @Test
    void overdue_lists_checked_out_loans_past_their_end() throws Exception {
        String id = device("DK-007");
        // a loan that started yesterday and ended an hour ago, still checked out
        Reservation late = new Reservation(UUID.randomUUID().toString(), "sam@example.com",
                NOW.minusSeconds(86400), NOW.minusSeconds(3 * 3600), Reservation.State.CHECKED_OUT, NOW.minusSeconds(86400), null);
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
                new Update().push("reservations", late), Device.class);
        Resp r = call("GET", "/api/reservations/overdue", null);
        assertThat(r.body().size()).isEqualTo(1);
        assertThat(r.body().get(0).get("borrower").asString()).isEqualTo("sam@example.com");
        assertThat(r.body().get(0).get("hoursOverdue").asLong()).isEqualTo(3);
    }

    @Test
    void concurrent_requests_for_one_slot_book_it_exactly_once() throws Exception {
        String id = device("DK-100");
        int n = 50;
        var pool = Executors.newFixedThreadPool(n);
        var go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            final int k = i;
            results.add(pool.submit(() -> {
                go.await();
                return call("POST", "/api/devices/" + id + "/reservations",
                        booking("user" + k + "@example.com", "2027-07-01T09:00:00Z", "2027-07-02T09:00:00Z")).status();
            }));
        }
        go.countDown();
        int created = 0, conflicts = 0;
        for (Future<Integer> f : results) {
            int s = f.get();
            if (s == 201) created++;
            else if (s == 409) conflicts++;
        }
        pool.shutdown();
        long stored = mongo.findById(id, Device.class).reservations().stream().filter(Reservation::active).count();
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(n - 1);
        assertThat(stored).isEqualTo(1);

        int naive = naiveDoubleBookings(n);
        String out = "{\n  \"concurrent_requests\": " + n + ",\n  \"atomic_conditional_update\": {\"bookings_created\": "
                + created + ", \"conflicts\": " + conflicts + "},\n  \"naive_read_then_write\": {\"bookings_created\": "
                + naive + "}\n}\n";
        Path resultsDir = Path.of("..", "results");
        Files.createDirectories(resultsDir);
        Files.writeString(resultsDir.resolve("concurrency.json"), out);
        System.out.println("concurrency: atomic=" + created + " naive=" + naive);
    }

    /** The bug the atomic update prevents: read the device, check for overlaps in code, then write. */
    int naiveDoubleBookings(int n) throws Exception {
        mongo.insert(new Device("naive", "DK-NAIVE", "naive", Device.Kind.CONSOLE, "x", false, null));
        var pool = Executors.newFixedThreadPool(n);
        var go = new CountDownLatch(1);
        Instant s = Instant.parse("2027-07-01T09:00:00Z"), e = Instant.parse("2027-07-02T09:00:00Z");
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                Device d = mongo.findById("naive", Device.class);
                boolean free = d.reservations().stream().noneMatch(r -> r.active() && r.overlaps(s, e));
                if (free) {
                    Reservation r = new Reservation(UUID.randomUUID().toString(), "x@example.com", s, e,
                            Reservation.State.BOOKED, null, null);
                    mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is("naive")),
                            new Update().push("reservations", r), Device.class);
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        return mongo.findById("naive", Device.class).reservations().size();
    }
}
