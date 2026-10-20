package dev.aaryan.loaner.device;

import java.time.Instant;

public record Reservation(
        String id,
        String borrower,
        Instant start,
        Instant end,
        State state,
        Instant checkedOutAt,
        Instant returnedAt) {

    public enum State { BOOKED, CHECKED_OUT, RETURNED, CANCELLED }

    /** Active reservations block the slot; returned and cancelled ones free it. */
    public boolean active() {
        return state == State.BOOKED || state == State.CHECKED_OUT;
    }

    public boolean overlaps(Instant from, Instant to) {
        return start.isBefore(to) && end.isAfter(from);
    }
}
