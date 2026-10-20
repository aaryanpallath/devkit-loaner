# Dev Kit Loaner — Full-Stack Internal IT App
Tech: Java, Spring Boot, Spring Security, React, TypeScript, MongoDB, AWS (ECS, Lambda, DocumentDB, CDK), Docker, Bash
- Built a Spring Boot REST API and React/TypeScript frontend for lending dev kits and consoles, with availability search, bookings, checkout/return and overdue tracking, secured with SSO JWTs and role-based access.
- Prevented double-bookings with an atomic conditional MongoDB update: 50 simultaneous requests for one slot created exactly 1 booking, where a naive read-then-write created 10-12 across 5 trials.
- Defined AWS infrastructure in CDK (ECS Fargate behind an internal load balancer, DocumentDB, a Python Lambda on a schedule, CloudFront) and set up GitHub Actions CI with a Docker Compose end-to-end smoke test in Bash.
