# Dev Kit Loaner — Full-Stack Internal IT App
Tech: Java, Spring Boot, React, TypeScript, MongoDB, AWS (ECS, Lambda, DocumentDB, CDK), Docker, Bash, GitHub Actions
- Built a Spring Boot REST API and React/TypeScript frontend for lending dev kits and consoles, with availability search, bookings, checkout/return and overdue tracking.
- Prevented double-bookings with an atomic conditional MongoDB update: 50 simultaneous requests for one slot created exactly 1 booking, where a naive read-then-write created 17.
- Defined AWS infrastructure in CDK (ECS Fargate, DocumentDB, a Python Lambda on an EventBridge schedule, S3 + CloudFront) and set up GitHub Actions CI with a Docker Compose end-to-end smoke test in Bash.
