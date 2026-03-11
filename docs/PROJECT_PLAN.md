# Project Plan: Notification Delivery Platform

Everything runs locally using Docker Compose (PostgreSQL, RabbitMQ, Redis, MailHog — all in containers, one command to start). No cloud, no external accounts, no setup hassle.

---

## MUST HAVE — The MVP

A working system where a notification goes in and an email comes out. Naive but functional.

### Architecture & Planning
1. Draw a system diagram showing all components and how they connect (API → database → outbox → queue → worker → email provider)
2. Design the database schema — what tables exist, what columns, how they relate to each other
3. Decide on the notification lifecycle — what states a notification goes through from creation to delivery (or failure)
4. Decide how the outbox pattern will work — how often do we poll, how do we mark messages as "published"
5. Agree on the API contract — what the request and response JSON looks like, which URLs, which status codes

### Setup & Learning
6. Everyone learns the basics of Spring Boot (building REST APIs, connecting to a database)
7. Set up Docker Compose so everyone can start PostgreSQL, RabbitMQ, Redis (not part of mvp), and MailHog/mailpit with one command
8. Set up the Spring Boot project with connections to all services verified

### The Database
9. Build the database tables: notifications, delivery attempts, tenants, API keys, and the outbox table
10. Create seed data so we have a test tenant and API key ready to use during development

### The API
11. Build the endpoint that accepts a notification request, saves it to the database and the outbox in one transaction, and returns 202 with a notification ID
12. Build the endpoint that lets you check the status of a notification by its ID
13. Add request validation — reject bad requests with clear error messages

### The Pipeline
14. Build the outbox publisher — a background process that reads unpublished messages from the outbox table and sends them to RabbitMQ
15. Build the email worker — a process that picks up messages from RabbitMQ, sends an email via MailHog, and updates the notification status in the database
16. Make sure the worker properly acknowledges messages to RabbitMQ (so crashed messages get redelivered, not lost)

### Failure Handling
17. Add retry logic — when email delivery fails temporarily, retry with increasing wait times (exponential backoff + jitter)
18. Set a maximum number of retries — after that, move the notification to the dead-letter queue and mark it as permanently failed
19. Log every delivery attempt (success or failure) with timestamp and error details

### Basic Multi-Tenancy
20. Add API key authentication — every request must include a valid key that maps to a tenant
21. Make sure all database queries are scoped to the tenant — Tenant A cannot see Tenant B's notifications

### Testing the MVP
22. Write integration tests that prove the full flow works: send a request → notification arrives in MailHog
23. Write tests that prove failure handling works: force an email failure → notification retries → eventually succeeds or goes to DLQ

---

## SHOULD HAVE — Engineering Depth

This is where the project becomes interesting and produces material for the report. The system already works — now we make it robust, fair, and provably correct.

### Reliability Hardening
24. Add idempotency — if the same notification is submitted twice (same idempotency key), don't send it twice. Use Redis to track recently-seen keys.
25. Add per-tenant rate limiting — each tenant has a maximum number of notifications per second. If they exceed it, return 429 (Too Many Requests). Implemented with a token bucket algorithm in Redis.
26. Add system-wide backpressure — if the queue gets dangerously deep, the API starts rejecting new requests to protect the system

### Webhook Delivery (Second Channel)
27. Build a webhook worker — delivers notifications as HTTP POST requests to the tenant's configured URL
28. Add channel routing — based on the notification's channel field, route it to either the email worker or the webhook worker
29. Use WireMock as a fake webhook receiver for testing

### The Fairness Experiment (Core Challenge 2)
30. Implement shared queue strategy — all tenants share one FIFO queue (this is what the MVP already does)
31. Implement per-tenant queue strategy — each tenant gets their own queue, workers consume from all queues in round-robin
32. Add a configuration toggle to switch between the two strategies without code changes
33. Write load test scripts (k6) that simulate unfair conditions: one tenant sending thousands of messages while another sends just a few
34. Run both strategies under the same load and measure the impact on each tenant's delivery latency

### Advanced Testing
35. Build a chaos testing setup — a way to kill workers and restart RabbitMQ mid-delivery, then verify that every notification still reaches a terminal state (zero loss)
36. Build a test oracle — generate a known set of notifications, send them all, check that every single one was delivered or explicitly failed
37. Collect and format all test results for the report — graphs, latency percentiles, comparison tables

---

## COULD HAVE — Making It a Real Product

If we have time after the core engineering work is done, these features make the system more complete and more impressive for the presentation.

### Security Features
38. Add HMAC-SHA256 webhook signing — sign webhook payloads so receivers can verify they came from our platform
39. Add replay protection — include timestamps and nonces so signed webhooks can't be replayed

### Usability Features
40. Add a simple template engine — let tenants define message templates with variables like `{{name}}` and `{{resetLink}}`
41. Add user preferences — let end users opt out of certain notification channels
42. Set up Grafana dashboards — connect to PostgreSQL and visualize delivery rates, failure rates, and queue depth per tenant
43. Generate API documentation automatically (Swagger/OpenAPI)
44. Set up CI/CD — run tests automatically on every push to GitHub

### Full SaaS Product
45. Build a simple web UI where tenants can log in, view their notifications, see failure logs, and manage settings
46. Build API key management — tenants can generate, rotate, and revoke their own API keys through the UI
47. Add a tenant onboarding flow — sign up, create an account, get API keys

### Cloud & Scale
48. Deploy the system to a cloud VM (not just local Docker)
49. Run load tests against the cloud deployment to get realistic performance numbers
50. Experiment with scaling — add more workers, measure how throughput changes linearly

---

## Report Writing

Not a separate phase — write as you build:

- After architecture decisions → write about why you chose what you chose
- After the MVP works → write about the outbox pattern and why it matters
- After adding retry/DLQ → write about at-least-once delivery and the thundering herd
- After the fairness experiment → write up the comparison with graphs and analysis
- After chaos tests → write about what broke and what survived
- Final weeks → introduction, conclusion, editing, formatting
