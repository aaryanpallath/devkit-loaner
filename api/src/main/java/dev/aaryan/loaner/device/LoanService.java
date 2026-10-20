package dev.aaryan.loaner.device;

import static org.springframework.data.mongodb.core.query.Criteria.where;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import dev.aaryan.loaner.device.Reservation.State;

@Service
public class LoanService {

    public static final Duration MAX_LOAN = Duration.ofDays(14);
    private static final List<State> ACTIVE = List.of(State.BOOKED, State.CHECKED_OUT);

    private final MongoTemplate mongo;
    private final Clock clock;

    public LoanService(MongoTemplate mongo, Clock clock) {
        this.mongo = mongo;
        this.clock = clock;
    }

    public Device create(Device d) {
        return mongo.insert(new Device(null, d.assetTag(), d.name(), d.kind(), d.location(), false, null));
    }

    public Device get(String id) {
        Device d = mongo.findById(id, Device.class);
        if (d == null) throw new NotFound("device " + id);
        return d;
    }

    /** Devices of a kind, optionally only those free for the whole window [from, to). */
    public List<Device> list(Device.Kind kind, Instant from, Instant to) {
        Criteria c = where("retired").is(false);
        if (kind != null) c = c.and("kind").is(kind);
        if (from != null && to != null) c = c.and("reservations").not().elemMatch(overlapping(from, to));
        return mongo.find(Query.query(c), Device.class);
    }

    /**
     * Books a slot atomically: the update only matches if the device exists, is not retired, and has no active
     * reservation overlapping [start, end). A non-match is then classified as 404 or 409.
     */
    public Reservation reserve(String deviceId, dev.aaryan.loaner.security.Caller caller, Instant start, Instant end) {
        validateWindow(start, end);
        Reservation r = new Reservation(UUID.randomUUID().toString(), caller.subject(), caller.email(), start, end,
                State.BOOKED, null, null);
        Query q = Query.query(where("_id").is(deviceId).and("retired").is(false)
                .and("reservations").not().elemMatch(overlapping(start, end)));
        if (mongo.updateFirst(q, new Update().push("reservations", r), Device.class).getModifiedCount() == 1) {
            return r;
        }
        Device d = get(deviceId);
        if (d.retired()) throw new Conflict("device is retired");
        throw new Conflict("device is already reserved for part of that time");
    }

    public Reservation checkout(String deviceId, String reservationId) {
        return transition(deviceId, reservationId, State.BOOKED, State.CHECKED_OUT, "checkedOutAt");
    }

    public Reservation giveBack(String deviceId, String reservationId) {
        return transition(deviceId, reservationId, State.CHECKED_OUT, State.RETURNED, "returnedAt");
    }

    /** Borrowers may cancel only their own bookings; the IT desk may cancel any. */
    public Reservation cancel(String deviceId, String reservationId, dev.aaryan.loaner.security.Caller caller) {
        Reservation r = find(get(deviceId), reservationId);
        if (!caller.admin() && !caller.subject().equals(r.borrowerId())) {   // legacy bookings: IT desk only
            throw new Forbidden("you can only cancel your own bookings");
        }
        return transition(deviceId, reservationId, State.BOOKED, State.CANCELLED, null);
    }

    /** Checked-out reservations whose end has passed, oldest first. */
    public List<Overdue> overdue() {
        Instant now = clock.instant();
        Query q = Query.query(where("reservations").elemMatch(where("state").is(State.CHECKED_OUT).and("end").lt(now)));
        return mongo.find(q, Device.class).stream()
                .flatMap(d -> d.reservations().stream()
                        .filter(r -> r.state() == State.CHECKED_OUT && r.end().isBefore(now))
                        .map(r -> new Overdue(d.id(), d.assetTag(), d.name(), r.id(), r.borrower(), r.end(),
                                Duration.between(r.end(), now).toHours())))
                .sorted((a, b) -> a.due().compareTo(b.due()))
                .toList();
    }

    public record Overdue(String deviceId, String assetTag, String deviceName, String reservationId,
                          String borrower, Instant due, long hoursOverdue) {}

    private Reservation transition(String deviceId, String rid, State from, State to, String stampField) {
        Query q = Query.query(where("_id").is(deviceId)
                .and("reservations").elemMatch(where("id").is(rid).and("state").is(from)));
        Update u = new Update().set("reservations.$.state", to);
        if (stampField != null) u.set("reservations.$." + stampField, clock.instant());
        if (mongo.updateFirst(q, u, Device.class).getModifiedCount() == 1) {
            return find(get(deviceId), rid);
        }
        Reservation current = find(get(deviceId), rid);
        throw new Conflict("reservation is " + current.state() + ", expected " + from);
    }

    private static Reservation find(Device d, String rid) {
        return d.reservations().stream().filter(r -> r.id().equals(rid)).findFirst()
                .orElseThrow(() -> new NotFound("reservation " + rid));
    }

    private static Criteria overlapping(Instant start, Instant end) {
        return where("state").in(ACTIVE).and("start").lt(end).and("end").gt(start);
    }

    private void validateWindow(Instant start, Instant end) {
        if (start == null || end == null || !start.isBefore(end)) throw new BadRequest("start must be before end");
        if (Duration.between(start, end).compareTo(MAX_LOAN) > 0) throw new BadRequest("loans are limited to 14 days");
        if (end.isBefore(clock.instant())) throw new BadRequest("cannot book a slot that has already ended");
    }

    public static class NotFound extends RuntimeException { NotFound(String m) { super(m + " not found"); } }
    public static class Conflict extends RuntimeException { Conflict(String m) { super(m); } }
    public static class BadRequest extends RuntimeException { BadRequest(String m) { super(m); } }
    public static class Forbidden extends RuntimeException { Forbidden(String m) { super(m); } }
}
