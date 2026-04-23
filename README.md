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

### Testing tools

#### Load testing

```bash
tests/load/run-load-test.sh
```

#### Compare load test runs

```bash
tests/load/compare.sh
``` 

#### Resilience testing

```bash
tests/resilience/common.sh
tests/resilience/rabbitmq-failure.sh
tests/resilience/redis-failure.sh
tests/resilience/run-all.sh
```

#### API Test Key

```bash
my-test-key-123
```

#### POST requests
```bash
# Send a test email
curl -X POST http://localhost:8080/api/v1/notifications \
  -H "X-API-Key: my-test-key-123" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "EMAIL",
    "recipient": "test@example.com",
    "subject": "Test Email",
    "content": "This is a test email.",
    "idempotencyKey": "test-001"
  }'

# Send a test webhook
curl -X POST http://localhost:8080/api/v1/notifications \
  -H "X-API-Key: my-test-key-123" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "webhook",
    "recipient": "https://example.com/webhook",
    "subject": "Test Webhook",
    "content": "This is a test webhook."
  }'
```

### UI's


#### Adminer (database):

**Link:** http://localhost:8081

**Logging in:**
| Field    | Value                 |
| -------- | --------------------- |
| System   | PostgreSQL            |
| Server   | postgres              |
| Username | dev                   |
| Password | dev                   |
| Database | notification_platform |

#### RabbitMQ (queues):

**Link:** http://localhost:15673

| Field    | Value                 |
| -------- | --------------------- |
| Username | dev                   |
| Password | dev                   |

#### Mailpit (emails):

**Link:** http://localhost:8025

#### Swagger (API documentation)

**Link:** http://localhost:8080

#### Actuator (health checks)

**Link:** http://localhost:8080/actuator/health


## Team

- August Hansen
- Anton Mervig
- Ali Hamza Zeb

## License

University project — DTU, Spring 2026.
