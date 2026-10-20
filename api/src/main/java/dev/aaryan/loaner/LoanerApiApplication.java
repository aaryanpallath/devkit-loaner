package dev.aaryan.loaner;

import java.time.Clock;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

@SpringBootApplication
public class LoanerApiApplication {

    public static void main(String[] args) {
        SpringApplication.run(LoanerApiApplication.class, args);
    }

    /** Injected so tests can fix "now" when checking overdue loans and past-dated bookings. */
    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
