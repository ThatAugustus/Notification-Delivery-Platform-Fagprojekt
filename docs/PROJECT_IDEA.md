# Notification Delivery Platform — Project Idea Document

> **Read this before our first planning session.** This document explains what we're building, why it matters, what's hard about it, and how we'll divide the work.

---

## Part 1: What Are We Building?

### The One-Sentence Version

We're building a **backend service that other applications plug into to handle their important notifications** — password resets, fraud alerts, payment confirmations — with guaranteed delivery, even when things break.

### The Slightly Longer Version

Imagine you're a developer at a company. Your app needs to send a "Your password was reset" email to a user. The obvious approach:

```java
// The naive way (what most apps do)
emailProvider.send("user@example.com", "Password Reset", "Your password was changed.");
// What if the email provider is down right now? → Email is lost. User is locked out.
// What if our server crashes during this call? → Email is lost. Nobody knows.
// What if we retry and the provider actually DID send it? → User gets 2 emails.
```

This is fine for a small app. But when you have thousands of notifications per second across multiple applications, this falls apart. Big companies solve this by building dedicated notification infrastructure. Some even sell it:

- **AWS SNS** — Amazon charges per notification to handle this for you
- **Novu** — raised $20M in funding specifically to solve this problem
- **Courier, Knock, MagicBell** — all VC-funded companies in this space

**We're building a simplified version of this.** Not to compete with AWS, but because the engineering challenges behind it are real, well-studied, and deeply educational.

### How It Works (The Flow)

```
┌────────────────────┐
│  Tenant A's App    │   (e.g., a banking app)
│  Tenant B's App    │   (e.g., an e-commerce site)
└──────────┬─────────┘
           │  POST /api/v1/notify
           │  { "user": "123", "channel": "email", "payload": {...} }
           ▼
┌────────────────────────────────────────────────────┐
│                   OUR SYSTEM                        │
│                                                     │
│  ┌─────────┐    ┌──────────┐    ┌───────────────┐  │
│  │ REST API │──▶│ Outbox   │──▶│  RabbitMQ     │  │
│  │         │    │ (in DB)  │    │  (queue)      │  │
│  └─────────┘    └──────────┘    └──────┬────────┘  │
│                                        │            │
│                                 ┌──────▼────────┐  │
│                                 │  Workers      │  │
│                                 │  (email,      │  │
│                                 │   webhook)    │  │
│                                 └──────┬────────┘  │
│                                        │            │
│                                 ┌──────▼────────┐  │
│                                 │  PostgreSQL   │  │
│                                 │  (log every   │  │
│                                 │   attempt)    │  │
│                                 └───────────────┘  │
└────────────────────────────────────────────────────┘
           │
           ▼
┌────────────────────┐
│  Email Provider    │   (MailHog in our dev environment)
│  Webhook Endpoint  │   (WireMock in our dev environment)
└────────────────────┘
```

**Key thing to understand:** We never call real email services (Gmail, SendGrid). During development and testing, we use **MailHog** — a fake email server running in Docker that catches emails and lets us inspect them in a web UI. For webhooks, we use **WireMock** — a fake HTTP server we can configure to return success, errors, or timeouts. This means **everything runs locally, no accounts needed, fully reproducible.**

---

## Part 2: What Problems Are We Solving?

The project has **two core engineering challenges**. Everything else (API design, database schema, security features) is standard software development that supports these two challenges.

### Core Challenge 1: Reliable Delivery in an Asynchronous System

#### What "asynchronous" means here

When a tenant sends us a notification request, we DON'T send the email immediately and wait for a response. That would be **synchronous** — simple but slow (2-5 seconds per notification, server is blocked the entire time).

Instead, we:
1. Save the notification to the database ✅
2. Return `202 Accepted` to the caller immediately ✅
3. A separate **worker** process picks it up from a queue and delivers it later

This is **asynchronous processing** — the API and the delivery happen at different times, in different processes. It's fast (API responds in milliseconds), scalable (add more workers to deliver faster), and resilient (if a worker crashes, the message is still in the queue).

**But it introduces three problems that don't exist in synchronous systems:**

#### Problem 1: The Dual-Write Problem

We need to do TWO things when a notification arrives: (1) save it to the database and (2) put it in the message queue. These are two different systems.

```
API receives request
  → Write to PostgreSQL ✅ (success)
  → Publish to RabbitMQ ❌ (app crashes before this happens)
  
Result: Notification exists in DB but is never delivered. Nobody knows.
```

The solution is called the **transactional outbox pattern**: instead of publishing directly to RabbitMQ, we write the notification AND a "to be published" record to the database in a single transaction (atomic — both happen or neither happens). A separate process reads the outbox table and publishes to RabbitMQ. If it fails, it just tries again — the data is safe in the database.

#### Problem 2: The Acknowledgment Gap

A worker picks up a message from RabbitMQ, sends the email, the email provider says "200 OK", and then... the worker crashes before it can tell RabbitMQ "I'm done with this message." RabbitMQ thinks the message was never processed and gives it to another worker. The user gets the email **twice**.

This is a fundamental property of **at-least-once delivery** — the strongest guarantee you can get in a distributed system without extremely expensive coordination. True "exactly once" delivery is theoretically impossible in the general case (look up the Two Generals Problem if you're curious).

Our solution: **idempotency keys**. Every notification has a unique ID. Before delivering, the worker checks: "Have I already delivered this ID?" If yes → skip. If no → deliver and record the ID. We store these keys in **Redis** (an in-memory database that's very fast for lookups).

#### Problem 3: The Thundering Herd

The email provider goes down for 5 minutes. 10,000 notifications pile up in the queue. The provider comes back. All 10,000 workers retry simultaneously. The provider collapses again under the load.

The solution: **exponential backoff with jitter**.
- Exponential backoff: wait 2s, then 4s, then 8s, then 16s between retries (exponentially increasing)
- Jitter: add a random offset so retries aren't synchronized across workers

Without jitter, all 10,000 retries would hit at exactly 2s, then exactly 4s, etc. — still a spike. With jitter, they spread out randomly. This is a well-studied technique (AWS published a detailed analysis of it).

---

### Core Challenge 2: Multi-Tenant Fairness (The Noisy Neighbor Problem)

#### What "multi-tenant" means

Our platform serves **multiple applications** (we call them "tenants"). Tenant A might be a banking app. Tenant B might be an e-commerce site. They share the same infrastructure (same queue, same workers, same database).

This is efficient, but it creates a problem:

#### The Starvation Scenario

```
Tenant A: Sends 100,000 payment receipts (legitimate, high volume)
Tenant B: Sends 1 fraud alert (critical, needs to arrive NOW)

In a simple First-In-First-Out queue:
  → Tenant B's fraud alert sits behind 100,000 messages
  → Delivered hours later
  → User's money is stolen
```

This is called the **noisy neighbor problem** — one tenant's high volume degrades service for everyone else. It's the same class of problem as CPU scheduling in an operating system: a shared resource must be allocated fairly.

#### What we'll do about it

Two mechanisms:

1. **Per-tenant rate limiting** — Each tenant gets a maximum number of notifications per second. If they exceed it, excess requests are rejected (or queued with lower priority). We implement this using a **token bucket algorithm** in Redis. Think of it like a bucket that fills with tokens at a steady rate. Each notification costs one token. When the bucket is empty, the tenant must wait.

2. **Queue partitioning** — Instead of one shared queue, we explore giving each tenant their own queue, with workers consuming from all queues in round-robin. This prevents one tenant's flood from blocking others. **We'll implement BOTH a shared queue and per-tenant queues, benchmark them under load, and compare the results.** This comparison is the strongest part of our project for the report.

---

## Part 3: The Tech Stack (What Does What)

| Technology | What it is | Why we use it |
|---|---|---|
| **Java 21 + Spring Boot 4** | Web framework | We know Java. Spring Boot gives us REST API, dependency injection, and integrations with everything else on this list. |
| **PostgreSQL** | Relational database | Stores notifications, delivery attempts, tenants, the outbox table. Durable — if the app crashes, data is safe. |
| **RabbitMQ** | Message queue broker | The "pipe" between our API and our workers. Messages sit in the queue until a worker picks them up. If a worker crashes, the message goes back in the queue. |
| **Redis** | In-memory key-value store | Extremely fast reads/writes. We use it for: idempotency key lookups, rate limiting counters, and caching. |
| **Docker Compose** | Container orchestration | One command (`docker compose up`) starts PostgreSQL, RabbitMQ, Redis, and MailHog. Everyone gets the same environment. |
| **MailHog** | Fake email server | Catches emails locally. Has a web UI where you can see every email that was "sent." No real email accounts needed. |
| **WireMock** | Fake HTTP server | Simulates webhook endpoints. We can configure it to return errors, delays, or successes — crucial for testing retries. |
| **Testcontainers** | Integration test library | Spins up real PostgreSQL + RabbitMQ in Docker containers just for tests. Tests run against real infrastructure, not mocks. |
| **k6** | Load testing tool | Simulates hundreds of concurrent users hitting our API. We use it to measure throughput, latency, and fairness under load. |

---

## Part 4: What's In Scope, What's Not

### MUST HAVE (The project fails without these)

| Feature | Why it's essential |
|---|---|
| REST API (accept notification requests) | Entry point for the whole system |
| PostgreSQL schema (notifications, tenants, delivery_attempts, outbox) | Persistent storage + audit trail |
| Transactional outbox pattern | Solves the dual-write problem (Core Challenge 1) |
| RabbitMQ integration (publish from outbox, consume in workers) | Async delivery pipeline |
| Email worker (delivers via MailHog) | At least one working delivery channel |
| Webhook worker (HTTP POST to tenant's URL) | Second channel, also needed for HMAC signing |
| Retry engine (exponential backoff + jitter) | Core Challenge 1 — failure recovery |
| Dead-letter queue (messages that exhausted all retries) | "No silent loss" guarantee |
| Idempotency (Redis-based duplicate check) | Prevents double delivery |
| Multi-tenant data isolation (tenant_id on all tables) | Core Challenge 2 — basic isolation |
| API key authentication | Tenants must authenticate |
| Per-tenant rate limiting (token bucket in Redis) | Core Challenge 2 — noisy neighbor defense |
| Queue partitioning experiment (shared vs per-tenant queues) | Core Challenge 2 — best report material |
| Chaos tests (kill components, verify zero loss) | Proves Challenge 1 works |
| Load tests with k6 (measure fairness under asymmetric load) | Proves Challenge 2 works |
| Integration tests (Testcontainers) | E2E correctness verification |

### SHOULD HAVE (Strengthens the project, do after MUST)

| Feature | Effort | Why |
|---|---|---|
| HMAC-SHA256 webhook signing | 2-3 days | Proves webhook authenticity. Good security topic for report |
| Notification templates (simple variable substitution) | 1-2 days | Makes the system actually usable — `"Hello {{name}}"` |
| User channel preferences (opt-out of email) | 2-3 days | Affects routing logic, adds realism |
| Grafana dashboard (connected to PostgreSQL) | 1-2 days | Visual analytics — Grafana does the work, we just write SQL queries |
| Swagger/OpenAPI docs | 0.5 days | Auto-generated. Makes our API professional |
| CI/CD (GitHub Actions) | 1 day | Run tests on every push |

### STRETCH GOALS (If we finish early)

| Feature | Why |
|---|---|
| Simple admin dashboard (HTML) | Demo-friendly, helps presentation |
| Cloud deployment (VM) | Run load tests against a real server |

### ONLY if we have time
- Real email provider integration (we use MailHog)
- Template editor UI
- Tenant self-service onboarding portal
- SMS / mobile push
- Consumer-facing frontend
- WebSocket / in-app notification channel Third delivery type, fundamentally different (stateful) 

---

## Part 5: Open Design Decisions (To Discuss Together)

These are decisions we haven't locked in yet. We should discuss and decide as a group:

| Decision | Options | Trade-off |
|---|---|---|
| **Outbox polling strategy** | A) Poll database every N ms (`@Scheduled`) B) PostgreSQL `LISTEN/NOTIFY` (event-driven) | A is simpler but adds latency. B is faster but more complex to implement. |
| **What happens when rate limit is hit?** | A) Reject with 429 (tenant must retry) B) Queue the message but deprioritize it | A is simpler and honest. B is friendlier but risks memory issues. |
| **Retry policy configuration** | A) Hardcoded (same for all tenants) B) Configurable per tenant | A saves time. B is more realistic and demonstrates API design. |
| **How many retry attempts before DLQ?** | 3? 5? 10? | More retries = higher eventual delivery rate but longer time to detect permanent failures. |
| **Notification state machine** | Which states? ACCEPTED → QUEUED → PROCESSING → DELIVERED / FAILED / DEAD_LETTERED? | We need to agree on the states before building — this affects the database schema. |
| **Package structure** | By feature (`notification/`, `tenant/`, `delivery/`) or by layer (`controller/`, `service/`, `repository/`)? | By feature is generally better for larger projects. Let's decide. |
| **Branch strategy** | Feature branches + PR reviews? Trunk-based? | PRs with code review recommended — we'll catch bugs and learn from each other. |

---

## Part 6: Suggested Work Distribution

This is a suggestion — we should discuss and adjust based on preferences.

### Person 1: Queue & Reliability

**Owns:** The core delivery pipeline — everything from outbox to DLQ.

| Task | Weeks |
|---|---|
| RabbitMQ setup + Spring AMQP integration | 2-3 |
| Transactional outbox pattern (outbox table + publisher) | 3-4 |
| Retry engine (backoff, jitter, configurable attempts) | 5-6 |
| Dead-letter queue routing + monitoring | 7 |
| Idempotency layer (Redis key check before delivery) | 8 |
| Chaos tests (kill workers/broker, verify zero loss) | 9-10 |
| Load testing + benchmarking (with person 2) | Sprint |

### Person 2: Workers & Multi-Tenancy

**Owns:** Delivery workers + the fairness experiment.

| Task | Weeks |
|---|---|
| Email worker (Spring `JavaMailSender` → MailHog) | 3-4 |
| Webhook worker (HTTP POST + HMAC signing) | 5-6 |
| Per-tenant rate limiting (Redis token bucket) | 7-8 |
| Queue partitioning experiment (shared vs per-tenant) | 8-10 |
| Load testing — fairness measurement (k6) | Sprint |
| Grafana setup (if time) | Sprint |

### Person 3: API & Data Layer

**Owns:** REST API, database schema, authentication, and observability.

| Task | Weeks |
|---|---|
| Database schema + migrations (all tables) | 2-3 |
| REST API endpoints + request validation | 3-5 |
| API key authentication + tenant scoping | 5-6 |
| Notification state machine (status transitions + audit log) | 6-7 |
| Templates + user preferences (SHOULD HAVE) | 8-10 |
| Integration test suite (Testcontainers) | 9-10 |
| Swagger docs | Sprint |

### Shared Responsibilities (Everyone)

- **Docker Compose** setup (week 1-2)
- **Database design** (decide schema together, person 3 implements)
- **Report writing** (each person writes about their components)
- **Code review** (PR-based workflow)

---

## Part 7: Key Terms Glossary

| Term | What it means |
|---|---|
| **Asynchronous processing** | Instead of doing work immediately and making the caller wait, you accept the request, put it on a queue, and process it later in a separate thread/process. Faster for the caller, more scalable, but harder to guarantee correctness. |
| **Message queue (RabbitMQ)** | A system where producers put messages in, and consumers take messages out. Messages are stored durably — if no consumer is available, they wait. If a consumer crashes, the message goes back in the queue. Think of it as a to-do list that multiple workers share. |
| **Transactional outbox** | A pattern to solve the "I need to write to a database AND publish to a queue atomically" problem. Instead of publishing directly, you write a record to an "outbox" table in the same database transaction. A separate process reads the outbox and publishes to the queue. |
| **Idempotency** | An operation is idempotent if doing it twice has the same effect as doing it once. `DELETE FROM users WHERE id = 5` is idempotent (the user is gone either way). `INSERT INTO log (message) VALUES ('hello')` is NOT idempotent (you get two rows). We make delivery idempotent by checking if a notification ID was already delivered before sending. |
| **Dead-letter queue (DLQ)** | A special queue where messages go when they've failed too many times. Instead of retrying forever (which wastes resources), we move them to the DLQ for investigation. The notification is NOT lost — it's explicitly marked as failed. |
| **Exponential backoff** | A retry strategy where you wait longer between each retry: 2s, 4s, 8s, 16s... This avoids overwhelmning a recovering system with retries. |
| **Jitter** | A random offset added to retry timing. Without jitter, all 10,000 failed messages retry at exactly 2s, then exactly 4s — still a spike. With jitter, each retry happens at 2s ± random, spreading them out. |
| **Thundering herd** | When a large number of processes all try to do the same thing at the same time (e.g., retry after an outage), overwhelming the target system. Exponential backoff + jitter prevents this. |
| **Multi-tenancy** | Multiple independent applications (tenants) share one instance of your system. Like an apartment building — each tenant has their own space but shares the plumbing. |
| **Noisy neighbor** | A multi-tenancy problem where one tenant's heavy usage degrades performance for other tenants. Like a neighbor playing loud music — they're using a shared resource (the building's noise tolerance) unfairly. |
| **Token bucket** | A rate limiting algorithm. Imagine a bucket that fills with tokens at a steady rate (e.g., 10 tokens/second). Each request costs 1 token. If the bucket is empty, the request is rejected (or delayed). Allows bursts (bucket can hold N tokens) but limits sustained rate. |
| **HMAC-SHA256** | A way to "sign" data so the receiver can verify it came from you and wasn't tampered with. You combine the message body with a shared secret using a cryptographic hash. The receiver does the same calculation — if they get the same result, the message is authentic. |
| **Chaos testing** | Deliberately breaking your system (killing processes, disconnecting networks) to verify it recovers correctly. If you only test the happy path, you don't know if your retry/recovery logic actually works. |
| **At-least-once delivery** | A guarantee that every message will be delivered at least once — but possibly more than once (duplicates). This is the strongest practical guarantee in distributed systems. "Exactly once" would require the sender and receiver to always agree on what happened, which is theoretically impossible when the network can fail. |
