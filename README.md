# Dev Kit Loaner — Full-Stack Internal IT App (Spring Boot · React · MongoDB/DocumentDB · AWS)

An internal web app for an IT team that lends dev kits, consoles and test hardware to staff: browse devices, see
what is free for a date range, book a slot, check out and return, and get automatic reminders for overdue loans.
Built on the stack of a typical enterprise IT team: a **Java Spring Boot** API, a **React + TypeScript** frontend,
**MongoDB** (API-compatible with **AWS DocumentDB**), **Docker**, an **AWS Lambda** job, AWS infrastructure as code
for **ECS Fargate**, and **Bash** tooling, with CI for every part.

## Overview

| Part | Stack | Tests |
|---|---|---|
| `api/` | Java 21, Spring Boot 4, Spring Data MongoDB | 7 integration tests against a real embedded MongoDB, incl. a 50-request concurrency test |
| `web/` | React 19, TypeScript, Vite | 3 component tests (Vitest, Testing Library) |
| `lambda/` | Python 3.12 AWS Lambda (daily overdue reminders) | 3 unit tests |
| `infra/` | AWS CDK (TypeScript): ECS Fargate + ALB, DocumentDB, Lambda + EventBridge, S3 + CloudFront | 5 template assertions; `cdk synth` in CI |
| `scripts/` | Bash: end-to-end smoke test, local dev launcher | smoke test runs against the Docker Compose stack in CI |

## The interesting part: no double-bookings under load

Two people booking the same console at the same moment must not both succeed. Checking for overlaps in code and
then saving (read-then-write) has a race window. Here, a device's reservations live in its own document and a
booking is a single conditional update: *push this reservation only if no active reservation overlaps it*.
MongoDB and DocumentDB apply single-document updates atomically, so the check and the write cannot interleave.

From `results/concurrency.json`, 50 simultaneous requests for the same slot:

| Approach | Bookings created |
|---|---|
| Atomic conditional update (this API) | **1** (49 rejected with 409 Conflict) |
| Naive read-then-write (for comparison, in the test) | 17 in the recorded run (varies with timing) |

## API

| Method | Path | |
|---|---|---|
| GET | `/api/devices?kind=&from=&to=` | devices, optionally only those free for the whole window |
| POST | `/api/devices` | create (asset tags are unique) |
| POST | `/api/devices/{id}/reservations` | book `{borrower, start, end}`: 201, or 409 if any overlap |
| POST | `/api/devices/{id}/reservations/{rid}/checkout` · `/return` | state changes; illegal transitions are 409 |
| DELETE | `/api/devices/{id}/reservations/{rid}` | cancel a booking (frees the slot) |
| GET | `/api/reservations/overdue` | checked-out loans past their end, oldest first |

Rules: start before end, at most 14 days, no slots already ended, valid borrower email. Errors are RFC 9457
problem details.

## Run it

```bash
scripts/dev.sh                    # API on :8080 with an embedded MongoDB (no Docker needed) + React on :5173
scripts/smoke.sh                  # end-to-end check against a running API
docker compose up --build         # API + MongoDB in containers
```

Tests: `cd api && ./mvnw test` · `cd web && npm test` · `cd lambda && pytest` · `cd infra && npm test && npm run synth`.

## AWS architecture (`infra/`, synthesized, not deployed)

- **ECS Fargate** service (2 tasks) behind an Application Load Balancer, health-checked on `/actuator/health`,
  deployment circuit breaker with rollback.
- **DocumentDB** cluster in private subnets, encrypted, password generated into **Secrets Manager** and injected
  into the containers as secrets; only the API's security group can reach its port.
- **Lambda** (Python) on a daily **EventBridge** schedule calls the overdue endpoint and builds one reminder per
  borrower, escalating loans over 72 hours. Sending via SES is left as a hook so it never emails anyone by accident.
- **S3 + CloudFront** serve the React app over HTTPS, with `/api/*` routed to the load balancer.

## Design decisions

- **Embedded reservations + conditional update** for atomic, race-free booking (above).
- **State machine** for reservations: BOOKED → CHECKED_OUT → RETURNED, or BOOKED → CANCELLED; each transition is
  also a conditional update, so a double click cannot check a device out twice.
- **Injected `Clock`** so tests pin "now" for overdue and past-dated checks.
- **Real database in tests, no Docker required:** tests start an embedded `mongod` (flapdoodle), and so does
  `scripts/dev.sh`.
- **DocumentDB specifics** (TLS, no retryable writes) live in a Spring profile (`application-aws.properties`).

## Limitations

- No authentication: borrower is a free-text email. A real deployment would sit behind the company's SSO.
- Not deployed to AWS. Connecting to DocumentDB also needs the Amazon RDS CA bundle in the container's trust store,
  and generated passwords must be URL-safe for the connection string; both are deployment steps not done here.
- The naive comparison's double-booking count depends on thread timing and varies run to run.

## License

MIT (see `LICENSE`).

## AI assistance disclosure

Built with help from Claude (Anthropic) via Claude Code; I reviewed, tested and verified the code and results.
