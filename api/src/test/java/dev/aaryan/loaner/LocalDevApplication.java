package dev.aaryan.loaner;

import org.springframework.boot.SpringApplication;

/** Runs the API locally against an embedded MongoDB: `./mvnw spring-boot:test-run` (no Docker or install needed). */
public class LocalDevApplication {

    public static void main(String[] args) {
        System.setProperty("spring.mongodb.uri", EmbeddedMongo.uri("loaner-dev"));
        SpringApplication.from(LoanerApiApplication::main).run(args);
    }
}
