package dev.aaryan.loaner.web;

import java.time.Instant;
import java.util.List;

import dev.aaryan.loaner.device.Device;
import dev.aaryan.loaner.device.Reservation;
import dev.aaryan.loaner.security.Caller;

/**
 * What a caller may see of a device. Other people's bookings appear as time slots only: the borrower's email is
 * shown to that borrower and to the IT desk, and is null for everyone else.
 */
public record DeviceView(String id, String assetTag, String name, Device.Kind kind, String location,
                         List<Slot> reservations) {

    public record Slot(String id, Instant start, Instant end, Reservation.State state, boolean mine,
                       String borrower) {}

    static DeviceView of(Device d, Caller c) {
        return new DeviceView(d.id(), d.assetTag(), d.name(), d.kind(), d.location(),
                d.reservations().stream().filter(Reservation::active).map(r -> {
                    // null-safe: a booking with no owner id (made before ids were stored) belongs to no one but the
                    // IT desk; it is never matched by email, which could be reassigned
                    boolean mine = c.subject().equals(r.borrowerId());
                    return new Slot(mine || c.admin() ? r.id() : null, r.start(), r.end(), r.state(), mine,
                            mine || c.admin() ? r.borrower() : null);
                }).toList());
    }
}
