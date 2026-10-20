package dev.aaryan.loaner.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import dev.aaryan.loaner.device.Device;
import dev.aaryan.loaner.device.LoanService;
import dev.aaryan.loaner.device.Reservation;
import dev.aaryan.loaner.security.Caller;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

@RestController
@RequestMapping("/api")
public class LoanController {

    private final LoanService loans;

    public LoanController(LoanService loans) {
        this.loans = loans;
    }

    public record NewDevice(@NotBlank String assetTag, @NotBlank String name, @NotNull Device.Kind kind,
                            @NotBlank String location) {}

    /** The borrower is the authenticated caller; it is not accepted from the request body. */
    public record NewReservation(@NotNull Instant start, @NotNull Instant end) {}

    @GetMapping("/devices")
    public List<DeviceView> devices(@RequestParam(required = false) Device.Kind kind,
                                    @RequestParam(required = false) Instant from,
                                    @RequestParam(required = false) Instant to, JwtAuthenticationToken auth) {
        Caller c = Caller.of(auth);
        return loans.list(kind, from, to).stream().map(d -> DeviceView.of(d, c)).toList();
    }

    @PostMapping("/devices")
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceView create(@Valid @RequestBody NewDevice d, JwtAuthenticationToken auth) {
        return DeviceView.of(loans.create(new Device(null, d.assetTag(), d.name(), d.kind(), d.location(), false, null)),
                Caller.of(auth));
    }

    @GetMapping("/devices/{id}")
    public DeviceView device(@PathVariable String id, JwtAuthenticationToken auth) {
        return DeviceView.of(loans.get(id), Caller.of(auth));
    }

    @PostMapping("/devices/{id}/reservations")
    @ResponseStatus(HttpStatus.CREATED)
    public Reservation reserve(@PathVariable String id, @Valid @RequestBody NewReservation r,
                               JwtAuthenticationToken auth) {
        return loans.reserve(id, Caller.of(auth), r.start(), r.end());
    }

    @PostMapping("/devices/{id}/reservations/{rid}/checkout")
    public Reservation checkout(@PathVariable String id, @PathVariable String rid) {
        return loans.checkout(id, rid);
    }

    @PostMapping("/devices/{id}/reservations/{rid}/return")
    public Reservation giveBack(@PathVariable String id, @PathVariable String rid) {
        return loans.giveBack(id, rid);
    }

    @DeleteMapping("/devices/{id}/reservations/{rid}")
    public Reservation cancel(@PathVariable String id, @PathVariable String rid, JwtAuthenticationToken auth) {
        return loans.cancel(id, rid, Caller.of(auth));
    }

    @GetMapping("/reservations/overdue")
    public List<LoanService.Overdue> overdue() {
        return loans.overdue();
    }

    @ExceptionHandler(LoanService.NotFound.class)
    ResponseEntity<ProblemDetail> notFound(RuntimeException e) {
        return problem(HttpStatus.NOT_FOUND, e);
    }

    @ExceptionHandler(LoanService.Conflict.class)
    ResponseEntity<ProblemDetail> conflict(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, e);
    }

    @ExceptionHandler(LoanService.Forbidden.class)
    ResponseEntity<ProblemDetail> forbidden(RuntimeException e) {
        return problem(HttpStatus.FORBIDDEN, e);
    }

    @ExceptionHandler(LoanService.BadRequest.class)
    ResponseEntity<ProblemDetail> badRequest(RuntimeException e) {
        return problem(HttpStatus.BAD_REQUEST, e);
    }

    @ExceptionHandler(org.springframework.dao.DuplicateKeyException.class)
    ResponseEntity<ProblemDetail> duplicate(RuntimeException e) {
        return problem(HttpStatus.CONFLICT, new RuntimeException("asset tag already exists"));
    }

    private static ResponseEntity<ProblemDetail> problem(HttpStatus s, RuntimeException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(s, e.getMessage());
        p.setProperties(Map.of());
        return ResponseEntity.status(s).body(p);
    }
}
