# Notification Delivery Platform

Multi-tenant notification platform for the 02122 Software Technology project (DTU, Spring 2026).
Applications submit notifications over a REST API, and the platform delivers them to email and
webhook endpoints in the background — reliably, even when a delivery provider or the broker is
briefly down, and without letting one busy tenant starve the others when several share an instance.

Under the hood it uses a transactional outbox so an accepted notification isn't silently lost,
at-least-once delivery with idempotency and retries, and per-tenant rate limiting plus weighted
queueing so no single tenant can monopolise delivery. Built with Java 21 / Spring Boot, PostgreSQL,
RabbitMQ and Redis, wired together with Docker Compose. The full write-up is in the report; this
README is just how to run and test it. Furthermore we have a README in the tests/framework folder for the load and chaos test setup

## What you need

- Docker + Docker Compose
- Java 21 (only for running the app from Gradle or running the test suite)

## Run it

Pick one.

**A) Everything in Docker (one command).** Builds the app image and starts it with Postgres,
RabbitMQ, Redis, Mailpit and the monitoring stack. The first build takes a few minutes.

```bash
docker compose --profile full up -d --build
```

**B) Backing services in Docker, app from Gradle** (quicker to restart while working on it):

```bash
docker compose up -d        # postgres, rabbitmq, redis, mailpit, grafana, ...
./gradlew bootRun           # the app, on http://localhost:8080
```

Either way, wait until it reports healthy:

```bash
curl http://localhost:8080/actuator/health      # {"status":"UP"}
```

The database is pre-seeded with one tenant and a working API key, so you can send something right
away without creating anything first.

## Send a notification

The seeded key is `my-test-key-123`. Send an email:

```bash
curl -X POST http://localhost:8080/api/v1/notifications \
  -H "X-API-Key: my-test-key-123" \
  -H "Content-Type: application/json" \
  -d '{
    "channel": "EMAIL",
    "recipient": "alice@example.com",
    "subject": "Hello",
    "content": "First test message",
    "idempotencyKey": "demo-1"
  }'
```

You get back `202 Accepted` with an id. Delivery happens in the background, so give it a second or
two and open **Mailpit at http://localhost:8025**; the email is waiting there.

Check where a notification is in its lifecycle (it moves ACCEPTED -> QUEUED -> PROCESSING -> DELIVERED):

```bash
curl http://localhost:8080/api/v1/notifications/<id> \
  -H "X-API-Key: my-test-key-123"
```

`idempotencyKey` is required. Send the same one twice and you get the same notification back instead
of a second copy.

Webhooks work the same way: set `"channel": "WEBHOOK"` and add `"webhookUrl": "https://..."` pointing
at a reachable endpoint (a https://webhook.site URL is the easy way to watch it land).

## Manage tenants and API keys (optional)

Admin endpoints live under `/api/v1/admin` and use a separate admin key (`local-dev-fallback-key` in
dev). Create a tenant and mint a key for it:

```bash
# create a tenant
curl -X POST http://localhost:8080/api/v1/admin/tenants \
  -H "X-Admin-Key: local-dev-fallback-key" \
  -H "Content-Type: application/json" \
  -d '{"name": "acme", "defaultFromEmail": "noreply@acme.com"}'

# issue a key for it (the raw key is shown once, here)
curl -X POST http://localhost:8080/api/v1/admin/tenants/<tenantId>/api-keys \
  -H "X-Admin-Key: local-dev-fallback-key" \
  -H "Content-Type: application/json" \
  -d '{"name": "prod"}'
```

## Configuration

Application settings live in `src/main/resources/application.properties` — the ports, the
datasource, the RabbitMQ / Redis / mail connections, the per-tenant rate limits, and the admin-key
fallback are all defined there. Two profile-specific overrides sit beside it:

- `application-docker.properties` — used when the app runs inside Docker (the `--profile full` run
  sets `SPRING_PROFILES_ACTIVE=docker`); it points the app at the Docker service hostnames instead
  of `localhost`.
- `application-realmail.properties` — swaps Mailpit for a real SMTP server.

Some settings worth knowing in `application.properties`:

| Setting | What it does |
|---|---|
| `app.notification-queues.mode` | queueing strategy: `per-tenant` (one queue per tenant) or `shared` (one queue for everyone) |
| `app.outbox-fairness.weights.<tenant>=<n>` | per-tenant publishing weight for the weighted round-robin outbox — a tenant with weight 10 gets ~10× the delivery share; defaults to 1 |
| `app.rate-limit.enabled` / `.capacity` / `.refill-tokens` / `.refill-period-seconds` | per-tenant token-bucket rate limiting — turn it off, or change how fast a tenant may submit |
| `spring.mail.host` / `spring.mail.port` | point email at a real SMTP server instead of Mailpit (or use the `realmail` profile) |
| `admin.api-key` (env `ADMIN_API_KEY`) | the admin API key; defaults to `local-dev-fallback-key` for dev |

The seeded tenant and API key come from the Flyway migrations in `src/main/resources/db/migration`,
not from the properties file.

## Run the tests

Integration tests drive the full pipeline against real Postgres, RabbitMQ and Redis, which
Testcontainers starts automatically, so Docker must be running:

```bash
./gradlew test
```

There's also a correctness/load framework that pushes traffic through the real API, waits for the
pipeline to drain, and asserts that nothing was lost or delivered twice; the duplicate check is
against Mailpit, not just the database. It needs `python3` and `k6`, with the stack up and the app on
:8080, and prints a single PASS/FAIL scorecard:

```bash
python3 tests/framework/ndp_test.py
```

See `tests/framework/README.md` for the options.

## Where to look

| What | URL | Login |
|---|---|---|
| App health | http://localhost:8080/actuator/health | |
| Swagger / API docs | http://localhost:8080/swagger-ui/index.html | |
| Mailpit (delivered emails) | http://localhost:8025 | |
| Grafana (dashboards) | http://localhost:3000 | dev / dev |
| RabbitMQ (queues) | http://localhost:15673 | dev / dev |
| Adminer (database) | http://localhost:8081 | server `postgres`, db `notification_platform`, dev / dev |
| Prometheus (metrics) | http://localhost:9090 | |

## Stop it

```bash
docker compose --profile full down       # add -v to also wipe the data volumes
```

## Team

- Ali Hamza Zeb
- August Allon Sander Hansen
- Anton Marius Mervig Schrøder

DTU 02122, Spring 2026.
