# Notification Delivery Platform - Fagprojekt (Spring 2026)

A multi-tenant notification delivery platform built as a DTU Fagprojekt (Spring 2026).

## What Is This?

A standalone backend service that sits between applications and delivery providers (email, webhooks). Applications submit notifications through a REST API; the platform guarantees reliable delivery across multiple tenants sharing one instance.

Designed for **business-critical notifications** — password resets, fraud alerts, payment confirmations, invoices — where loss, significant delay, or duplication is unacceptable.

## Core Engineering Challenges

1. **Reliable Async Delivery** — Transactional outbox pattern, at-least-once processing, exponential backoff with jitter, dead-letter queues, idempotency
2. **Multi-Tenant Fairness** — Per-tenant rate limiting (token bucket), queue partitioning strategies, noisy neighbor prevention

## Tech Stack

| Component | Technology |
|---|---|
| Application | Java 21 + Spring Boot 4 |
| Database | PostgreSQL |
| Message Queue | RabbitMQ |
| Cache / Rate Limiting | Redis |
| Infrastructure | Docker Compose |
| Load Testing | k6 |
| Integration Testing | Testcontainers |

## Getting Started

### Prerequisites
- Java 21
- Docker & Docker Compose

### Run
```bash
docker compose up -d        # Start PostgreSQL, RabbitMQ, Redis
./gradlew bootRun            # Start the application
```

### Test
```bash
./gradlew test               # Unit + integration tests
```

## Team

- August Hansen
- Anton Mervig
- Ali Hamza Zeb

## License

University project — DTU, Spring 2026.
