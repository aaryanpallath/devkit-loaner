package dev.aaryan.loaner.device;

import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/**
 * A loanable device. Its reservations are embedded so that "book this slot only if nothing overlaps" is a single
 * atomic document update: MongoDB and DocumentDB guarantee atomicity per document, which is what prevents two
 * concurrent requests from double-booking the same device.
 */
@Document("devices")
public record Device(
        @Id String id,
        @Indexed(unique = true) String assetTag,
        String name,
        Kind kind,
        String location,
        boolean retired,
        List<Reservation> reservations) {

    public enum Kind { DEV_KIT, CONSOLE, HANDHELD, ACCESSORY }

    public Device {
        reservations = reservations == null ? new ArrayList<>() : reservations;
    }
}
