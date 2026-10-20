# Dev Kit Loaner — Full-Stack Internal IT App (Spring Boot · React · MongoDB/DocumentDB · AWS)

An internal web app for an IT team that lends dev kits, consoles and test hardware to staff: browse devices, see
what is free for a date range, book a slot, have the IT desk check it out and back in, and send automatic reminders
for overdue loans. Built on a typical enterprise IT stack: a **Java Spring Boot** API secured with company-SSO
tokens, a **React + TypeScript** frontend, **MongoDB** (API-compatible with **AWS DocumentDB**), **Docker**, an
**AWS Lambda** job, AWS infrastructure as code for **ECS Fargate**, and **Bash** tooling, with CI for every part.

## Overview

| Part | Stack | Tests |
|---|---|---|
| `api/` | Java 21, Spring Boot 4, Spring Security (OAuth2 JWT), Spring Data MongoDB | 16 integration tests against a real embedded MongoDB, incl. security rules and a 50-request concurrency test |
| `web/` | React 19, TypeScript, Vite | 5 component tests (Vitest, Testing Library) |
| `lambda/` | Python 3.12 AWS Lambda (daily overdue reminders) | 4 unit tests |
| `infra/` | AWS CDK (TypeScript): ECS Fargate, internal ALB, DocumentDB, Lambda + EventBridge, S3 + CloudFront | 6 template assertions; `cdk synth` in CI |
| `scripts/` | Bash: smoke test, JWT minting with `openssl`, key setup, dev launcher | 9-check smoke test against the Docker Compose stack in CI |

## The interesting part: no double-bookings under load

Two people booking the same console at the same moment must not both succeed. Checking for overlaps in code and
then saving (read-then-write) has a race window. Here, a device's reservations live in its own document and a
booking is a single conditional update: *push this reservation only if no active reservation overlaps it*.
MongoDB and DocumentDB apply single-document updates atomically, so the check and the write cannot interleave.

From `results/concurrency.json`, 50 simultaneous requests for the same slot:

| Approach | Bookings created |
|---|---|
| Atomic conditional update (this API) | **1** (49 rejected with 409 Conflict) |
| Naive read-then-write (comparison, 5 trials) | 10, 11, 11, 12, 12 |

## Security

- **Sign-in:** every API call needs a JWT from the company identity provider. The API verifies its signature (the
  IdP's published keys), expiry, **issuer**, and **audience**, so a token minted for another application is refused,
  and a token that carries an email must mark it verified.
- **Identity:** ownership is keyed on the token's immutable subject (`sub`), not the email address, which can change
  or be reassigned; the email is kept for display and reminders. The borrower always comes from the token, never
  from the request body.
- **Roles** (token `roles` claim): `ADMIN` (IT desk) creates devices and checks loans out and in; `REMINDER_SERVICE`
  (the Lambda) and `ADMIN` read the overdue report; borrowers book and cancel only their own bookings.
- **Privacy:** listings show other people's bookings as anonymous time slots; a borrower's email is visible only to
  that borrower and the IT desk.
- **Network (AWS):** the load balancer is internal with no internet-facing rule; CloudFront reaches it through a
  VPC origin and serves viewers over HTTPS only; DocumentDB accepts connections only from the API's security group;
  the Lambda runs inside the VPC and reads its service token from Secrets Manager at run time.
- **Local and CI:** `scripts/dev-keys.sh` creates a throwaway key pair (git-ignored) and `scripts/dev-token.sh`
  signs tokens with it, so the real security code path is exercised end to end.

## API

| Method | Path | Who |
|---|---|---|
| GET | `/api/devices?kind=&from=&to=` · `/api/devices/{id}` | any signed-in user |
| POST | `/api/devices` | IT desk |
| POST | `/api/devices/{id}/reservations` `{start, end}` | any signed-in user (201, or 409 if any overlap) |
| POST | `/api/devices/{id}/reservations/{rid}/checkout` · `/return` | IT desk |
| DELETE | `/api/devices/{id}/reservations/{rid}` | the borrower, or the IT desk |
| GET | `/api/reservations/overdue` | IT desk, reminder service |

Rules: start before end, at most 14 days, no slots already ended. Errors are RFC 9457 problem details.

## Run it

```bash
scripts/dev.sh                    # API on :8080 with an embedded MongoDB (no Docker needed) + React on :5173;
                                  # prints borrower and IT-desk tokens to paste into the sign-in box
scripts/smoke.sh                  # end-to-end check against a running API
scripts/dev-keys.sh && docker compose up --build    # API + MongoDB in containers
```

Tests: `cd api && ./mvnw test` · `cd web && npm test` · `cd lambda && pytest` · `cd infra && npm test && npm run synth`.

## AWS architecture (`infra/`, synthesized, not deployed)

ECS Fargate (2 tasks, health-checked on `/actuator/health`, circuit breaker with rollback) behind an internal ALB;
DocumentDB (encrypted, password in Secrets Manager, injected as container secrets) in private subnets; Python Lambda
on a daily EventBridge schedule inside the VPC; S3 + CloudFront for the React app, with `/api/*` routed to the ALB
through a CloudFront VPC origin. Deploy with
`cdk deploy -c oidcJwksUri=<IdP JWKS URL> -c oidcIssuer=<IdP issuer> -c oidcAudience=<API audience>`.

## Design decisions

- **Embedded reservations + conditional update** for atomic, race-free booking (above).
- **State machine**: BOOKED → CHECKED_OUT → RETURNED, or BOOKED → CANCELLED; each transition is a conditional update,
  so a double click cannot check a device out twice.
- **Injected `Clock`** so tests pin "now" for overdue and past-dated checks.
- **Real database and real token verification in tests**, no Docker required: an embedded `mongod` (flapdoodle),
  and RSA-signed JWTs minted per test run.
- **Environment-specific settings in Spring profiles**: `local` (dev public key) and `aws` (DocumentDB TLS settings,
  IdP JWKS).

## Limitations

- The React app accepts a pasted token instead of running the OIDC redirect flow with the company IdP.
- Not deployed to AWS. A real deployment also needs the Amazon RDS CA bundle in the container trust store for
  DocumentDB TLS, URL-safe generated passwords, an ACM certificate for HTTPS on the internal ALB if required, and
  the IdP client-credentials flow that refreshes the reminder service token.
- Email sending (SES) is a hook; reminders are logged.
- MongoDB 8.0.5 is pinned in Docker Compose because newer 8.0 images refuse to start on Linux 6.19+ kernels
  (MongoDB SERVER-121912), which GitHub's runners use.

## License

MIT (see `LICENSE`).

## AI assistance disclosure

Built with help from Claude (Anthropic) via Claude Code; I reviewed, tested and verified the code and results.
