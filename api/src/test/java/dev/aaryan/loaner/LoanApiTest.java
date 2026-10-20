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

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.util.Date;

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

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import dev.aaryan.loaner.device.Device;
import dev.aaryan.loaner.device.Reservation;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LoanApiTest {

    static final Instant NOW = Instant.parse("2027-06-15T12:00:00Z");
    static final JsonMapper JSON = JsonMapper.builder().build();
    static final HttpClient HTTP = HttpClient.newHttpClient();
    static final String ISSUER = "https://idp.test";
    static final String AUDIENCE = "loaner-api";

    static final java.security.KeyPair KEYS = rsa();

    static java.security.KeyPair rsa() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }

    }

    static String token(String email, String... roles) {
        return token("sub-" + email, email, true, ISSUER, AUDIENCE, roles);
    }

    static String token(String subject, String email, boolean verified, String issuer, String audience,
                        String... roles) {
        try {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder().subject(subject).issuer(issuer).audience(audience)
                    .claim("roles", List.of(roles)).expirationTime(new Date(System.currentTimeMillis() + 600_000));
            if (email != null) b.claim("email", email).claim("email_verified", verified);
            JWTClaimsSet claims = b.build();
            SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims);
            jwt.sign(new RSASSASigner((RSAPrivateKey) KEYS.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final String ADMIN = token("desk@example.com", "ADMIN");
    static final String ANA = token("ana@example.com");
    static final String BEN = token("ben@example.com");
    static final String REMINDER = token("reminder-client", null, false, ISSUER, AUDIENCE, "REMINDER_SERVICE");

    /** The same properties production uses: a verification key, the expected issuer and this API's audience. */
    @DynamicPropertySource
    static void config(DynamicPropertyRegistry r) throws Exception {
        r.add("spring.mongodb.uri", () -> EmbeddedMongo.uri("loaner-test"));
        Path pem = Files.createTempFile("loaner-test-key", ".pem");
        Files.writeString(pem, "-----BEGIN PUBLIC KEY-----\n"
                + java.util.Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(KEYS.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n");
        r.add("loaner.auth.public-key", () -> "file:" + pem);
        r.add("loaner.auth.issuer", () -> ISSUER);
        r.add("loaner.auth.audience", () -> AUDIENCE);
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
        return call(method, path, json, ADMIN);
    }

    Resp call(String method, String path, String json, String token) throws Exception {
        var b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).header("Content-Type", "application/json");
        if (token != null) b = b.header("Authorization", "Bearer " + token);
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

    static String booking(String start, String end) {
        return "{\"start\":\"" + start + "\",\"end\":\"" + end + "\"}";
    }

    Resp reserve(String id, String start, String end) throws Exception {
        return call("POST", "/api/devices/" + id + "/reservations", booking(start, end), ANA);
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
        call("DELETE", "/api/devices/" + id + "/reservations/" + first.body().get("id").asString(), null, ANA);
        assertThat(reserve(id, "2027-06-16T10:00:00Z", "2027-06-16T12:00:00Z").status()).isEqualTo(201);  // freed
    }

    @Test
    void invalid_requests_are_rejected() throws Exception {
        String id = device("DK-003");
        assertThat(reserve(id, "2027-06-18T09:00:00Z", "2027-06-17T09:00:00Z").status()).isEqualTo(400);
        assertThat(reserve(id, "2027-06-16T09:00:00Z", "2027-07-16T09:00:00Z").status()).isEqualTo(400);  // > 14 days
        assertThat(reserve(id, "2027-06-01T09:00:00Z", "2027-06-02T09:00:00Z").status()).isEqualTo(400);  // ended
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
        assertThat(call("DELETE", "/api/devices/" + id + "/reservations/" + rid, null, ANA).status()).isEqualTo(409);
    }

    @Test
    void availability_filter_excludes_booked_devices() throws Exception {
        String busy = device("DK-005");
        String free = device("DK-006");
        reserve(busy, "2027-06-20T09:00:00Z", "2027-06-22T09:00:00Z");
        Resp r = call("GET", "/api/devices?from=2027-06-21T00:00:00Z&to=2027-06-21T12:00:00Z", null, ANA);
        List<String> ids = new ArrayList<>();
        r.body().forEach(n -> ids.add(n.get("id").asString()));
        assertThat(ids).containsExactly(free);
    }

    @Test
    void overdue_lists_checked_out_loans_past_their_end() throws Exception {
        String id = device("DK-007");
        // a loan that started yesterday and ended an hour ago, still checked out
        Reservation late = new Reservation(UUID.randomUUID().toString(), "sub-sam", "sam@example.com",
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
                        booking("2027-07-01T09:00:00Z", "2027-07-02T09:00:00Z"), token("user" + k + "@example.com")).status();
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

        List<Integer> naive = new ArrayList<>();
        for (int trial = 0; trial < 5; trial++) naive.add(naiveDoubleBookings(n, "naive-" + trial));
        String out = "{\n  \"concurrent_requests\": " + n + ",\n  \"atomic_conditional_update\": {\"bookings_created\": "
                + created + ", \"conflicts\": " + conflicts + "},\n  \"naive_read_then_write\": {\"bookings_created_per_trial\": "
                + naive + "}\n}\n";
        Path resultsDir = Path.of("..", "results");
        Files.createDirectories(resultsDir);
        Files.writeString(resultsDir.resolve("concurrency.json"), out);
        System.out.println("concurrency: atomic=" + created + " naive=" + naive);
    }

    // ------------------------------------------------------------------ security
    @Test
    void requests_without_a_valid_token_are_rejected() throws Exception {
        assertThat(call("GET", "/api/devices", null, null).status()).isEqualTo(401);
        assertThat(call("GET", "/api/devices", null, "not.a.jwt").status()).isEqualTo(401);
        assertThat(call("GET", "/actuator/health", null, null).status()).isEqualTo(200);
    }

    @Test
    void tokens_from_another_issuer_or_for_another_app_are_rejected() throws Exception {
        assertThat(call("GET", "/api/devices", null, ANA).status()).isEqualTo(200);
        assertThat(call("GET", "/api/devices", null,
                token("sub-ana", "ana@example.com", true, "https://evil.test", AUDIENCE)).status()).isEqualTo(401);
        assertThat(call("GET", "/api/devices", null,
                token("sub-ana", "ana@example.com", true, ISSUER, "some-other-app")).status()).isEqualTo(401);
    }

    @Test
    void tokens_with_an_unverified_email_are_rejected() throws Exception {
        assertThat(call("GET", "/api/devices", null,
                token("sub-eve", "ana@example.com", false, ISSUER, AUDIENCE)).status()).isEqualTo(401);
    }

    @Test
    void ownership_follows_the_account_not_the_email_address() throws Exception {
        String id = device("DK-204");
        String rid = reserve(id, "2027-06-16T09:00:00Z", "2027-06-17T09:00:00Z").body().get("id").asString();
        // a different account that has (or later receives) the same email address is not the owner
        String sameEmailOtherAccount = token("sub-someone-else", "ana@example.com", true, ISSUER, AUDIENCE);
        assertThat(call("DELETE", "/api/devices/" + id + "/reservations/" + rid, null, sameEmailOtherAccount).status())
                .isEqualTo(403);
        assertThat(call("GET", "/api/devices/" + id, null, sameEmailOtherAccount).body()
                .get("reservations").get(0).get("mine").asBoolean()).isFalse();
    }

    @Test
    void bookings_without_an_owner_id_are_desk_managed_and_never_matched_by_email() throws Exception {
        String id = device("DK-205");
        Reservation legacy = new Reservation("legacy-1", null, "ana@example.com", NOW.plusSeconds(86400),
                NOW.plusSeconds(2 * 86400), Reservation.State.BOOKED, null, null);
        mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(id)),
                new Update().push("reservations", legacy), Device.class);
        Resp listing = call("GET", "/api/devices/" + id, null, ANA);
        assertThat(listing.status()).isEqualTo(200);
        assertThat(listing.body().get("reservations").get(0).get("mine").asBoolean()).isFalse();
        assertThat(call("DELETE", "/api/devices/" + id + "/reservations/legacy-1", null, ANA).status()).isEqualTo(403);
        assertThat(call("DELETE", "/api/devices/" + id + "/reservations/legacy-1", null, ADMIN).status()).isEqualTo(200);
    }

    @Test
    void only_the_it_desk_creates_devices_and_hands_them_out() throws Exception {
        assertThat(call("POST", "/api/devices",
                "{\"assetTag\":\"X-1\",\"name\":\"x\",\"kind\":\"CONSOLE\",\"location\":\"x\"}", ANA).status()).isEqualTo(403);
        String id = device("DK-200");
        String rid = reserve(id, "2027-06-16T09:00:00Z", "2027-06-17T09:00:00Z").body().get("id").asString();
        assertThat(call("POST", "/api/devices/" + id + "/reservations/" + rid + "/checkout", null, ANA).status()).isEqualTo(403);
        assertThat(call("POST", "/api/devices/" + id + "/reservations/" + rid + "/checkout", null, ADMIN).status()).isEqualTo(200);
    }

    @Test
    void borrowers_can_cancel_only_their_own_bookings() throws Exception {
        String id = device("DK-201");
        String rid = reserve(id, "2027-06-16T09:00:00Z", "2027-06-17T09:00:00Z").body().get("id").asString();
        assertThat(call("DELETE", "/api/devices/" + id + "/reservations/" + rid, null, BEN).status()).isEqualTo(403);
        assertThat(call("DELETE", "/api/devices/" + id + "/reservations/" + rid, null, ANA).status()).isEqualTo(200);
    }

    @Test
    void the_borrower_is_the_caller_not_whatever_the_body_says() throws Exception {
        String id = device("DK-202");
        Resp r = call("POST", "/api/devices/" + id + "/reservations",
                "{\"borrower\":\"ben@example.com\",\"start\":\"2027-06-16T09:00:00Z\",\"end\":\"2027-06-17T09:00:00Z\"}", ANA);
        assertThat(r.status()).isEqualTo(201);
        assertThat(r.body().get("borrower").asString()).isEqualTo("ana@example.com");
    }

    @Test
    void listings_hide_other_peoples_emails() throws Exception {
        String id = device("DK-203");
        reserve(id, "2027-06-16T09:00:00Z", "2027-06-17T09:00:00Z");
        JsonNode asBen = call("GET", "/api/devices/" + id, null, BEN).body().get("reservations").get(0);
        assertThat(asBen.get("borrower").isNull()).isTrue();
        assertThat(asBen.get("id").isNull()).isTrue();
        assertThat(asBen.get("mine").asBoolean()).isFalse();
        JsonNode asAna = call("GET", "/api/devices/" + id, null, ANA).body().get("reservations").get(0);
        assertThat(asAna.get("borrower").asString()).isEqualTo("ana@example.com");
        assertThat(asAna.get("mine").asBoolean()).isTrue();
        JsonNode asDesk = call("GET", "/api/devices/" + id, null, ADMIN).body().get("reservations").get(0);
        assertThat(asDesk.get("borrower").asString()).isEqualTo("ana@example.com");
    }

    @Test
    void overdue_report_is_for_the_it_desk_and_the_reminder_service_only() throws Exception {
        assertThat(call("GET", "/api/reservations/overdue", null, ANA).status()).isEqualTo(403);
        assertThat(call("GET", "/api/reservations/overdue", null, REMINDER).status()).isEqualTo(200);
        assertThat(call("GET", "/api/reservations/overdue", null, ADMIN).status()).isEqualTo(200);
    }

    /** The bug the atomic update prevents: read the device, check for overlaps in code, then write. */
    int naiveDoubleBookings(int n, String deviceId) throws Exception {
        mongo.insert(new Device(deviceId, "DK-" + deviceId, "naive", Device.Kind.CONSOLE, "x", false, null));
        var pool = Executors.newFixedThreadPool(n);
        var go = new CountDownLatch(1);
        Instant s = Instant.parse("2027-07-01T09:00:00Z"), e = Instant.parse("2027-07-02T09:00:00Z");
        List<Future<?>> fs = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            fs.add(pool.submit(() -> {
                go.await();
                Device d = mongo.findById(deviceId, Device.class);
                boolean free = d.reservations().stream().noneMatch(r -> r.active() && r.overlaps(s, e));
                if (free) {
                    Reservation r = new Reservation(UUID.randomUUID().toString(), "sub-x", "x@example.com", s, e,
                            Reservation.State.BOOKED, null, null);
                    mongo.updateFirst(Query.query(org.springframework.data.mongodb.core.query.Criteria.where("_id").is(deviceId)),
                            new Update().push("reservations", r), Device.class);
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : fs) f.get();
        pool.shutdown();
        return mongo.findById(deviceId, Device.class).reservations().size();
    }
}
