# NDP Test Framework — correctness gate + signpost

One command drives load through the whole pipeline, waits for it to drain,
**proves the system's guarantees**, and on failure tells you *where to look*.

```bash
# default run (uses config.json)
python3 tests/framework/ndp_test.py

# quick overrides
python3 tests/framework/ndp_test.py --pattern spike --rate 300 --duration 60 --note "spike 300/s 60s"
python3 tests/framework/ndp_test.py --chaos                 # inject the faults defined in config.json
python3 tests/framework/ndp_test.py --bad-requests 0.1 --note "10% malformed requests"
python3 tests/framework/ndp_test.py --no-teardown           # leave the throwaway API keys in place
```

Needs `python3`, `k6`, and `docker`, with the compose stack up and the app on `:8080`.
Stdlib only — no pip installs.

## What it does (5 phases)

1. **Pre-flight** — checks the app is `UP` and the system has zero backlog (otherwise it
   waits, then aborts), and captures a DB-clock baseline so the run is scoped in time.
2. **Provision tenants** — ensures the tenants listed in `config.json` exist (creating any
   that are missing), restarts the app once if their workers aren't registered yet, and
   mints a throwaway API key per tenant.
3. **Load** — k6 sends uniquely-tagged messages (`constant | ramp | spike`), spread across
   tenants by their configured `weight`. Optionally injects chaos (pause/stop a container
   mid-run) and/or a fraction of malformed requests (`--bad-requests`).
4. **Drain** — polls until every message reaches a terminal state, or times out (a timeout
   means messages are stuck, which is itself a failure).
5. **Teardown** — revokes the throwaway keys; the tenants themselves are kept for reuse, so
   later runs skip the restart.

Between drain and teardown it runs the conservation checks and prints + saves a scorecard to
`tests/performance/results/framework-<runid>.json` (the `--note` text is slugged into the filename).

## The invariants it proves

| Invariant | Meaning |
|---|---|
| no silent loss | every accepted message reached DELIVERED/FAILED |
| ingest accounted | API-accepted count == rows persisted |
| unique mail content | no two messages collided (load-generator sanity) |
| outbox drained | nothing stuck unpublished |
| no duplicate delivery (attempts) | ≤ 1 SUCCESS per notification in `delivery_attempts` |
| no duplicate delivery (mailpit/escaped) | **ground truth** — no extra emails reached recipients |
| bad requests kept out | *(only with `--bad-requests`)* every malformed request was rejected, never persisted or delivered |

The two duplicate checks differ on purpose: the *attempts* check can be masked by app-side
dedup, so the *mailpit* check is what proves nothing escaped to a real inbox. (Caveat: above
Mailpit's `MP_MAX_MESSAGES` cap it under-counts, so it is reliable only below that volume.)

## Reading the result

- **VERDICT: PASS/FAIL** is the gate.
- On failure, each `[FAIL]` prints `→` **signposts** to the exact Grafana panel or SQL to debug,
  plus diagnostics (e.g. duplicate offenders split by `retry_count` and channel).
- The scorecard also reports, as **characterisation** (not pass/fail): delivery throughput
  (peak / avg / effective), a latency breakdown (`accept→published` vs `published→delivered`),
  and a per-tenant table (throughput share + p50/p95 end-to-end latency).

## Configuration (`config.json`)

The knobs you'll actually touch live under `load`, `chaos`, and `limits`:

| Key | Meaning |
|---|---|
| `load.pattern` / `rate` / `duration_seconds` | load shape, target msg/s, and how long |
| `load.channels` | `["EMAIL"]`, `["WEBHOOK"]`, or both |
| `load.tenants` | list of `{ "name", "weight" }`; weight sets each tenant's share of the rate |
| `load.bad_request_fraction` | fraction (0–1) of malformed requests (same as `--bad-requests`) |
| `chaos.enabled` + `chaos.faults` | each fault: `{ container, action (pause/stop), at_seconds, duration_seconds }` |
| `limits.drain_timeout_seconds` / `stall_seconds` | when to give up draining / declare the pipeline stuck |
| `save_delivery_curve` | if `true`, include the per-second delivery curve in the JSON (off by default) |

CLI flags (`--pattern`, `--rate`, `--duration`, `--bad-requests`, `--chaos`, `--note`,
`--no-teardown`) override the file for a single run.

## Scope & honesty (read before quoting numbers)

This is a **gate** (correctness) and a **signpost** (where to look) — not a benchmark.
Throughput figures are **indicative**: a single laptop run is noisy.

Deliberately *not* in the tool yet:
- repeated runs with variance, at multiple load levels;
- finer per-stage latency attribution (it already splits `accept→publish` vs `publish→deliver`;
  deeper splits would need stage-timestamp instrumentation);
- a controlled fairness experiment (a light tenant alone vs under a noisy neighbour) — the
  per-tenant table *measures* fairness, but doesn't isolate cause.

Use this to catch regressions and find *where* to dig; use Grafana + `pg_stat_statements` to find *why*.

### Inspecting slow queries with pg_stat_statements

```bash
# open a psql shell in the postgres container (\q to exit)
docker exec -it ndp-postgres psql -U dev -d notification_platform
```

```sql
-- confirm the extension is collecting
SELECT extname FROM pg_extension WHERE extname = 'pg_stat_statements';

-- reset before a run, then re-run this after, to read one run cleanly
SELECT pg_stat_statements_reset();

-- the most expensive queries
SELECT calls, total_exec_time, mean_exec_time, query
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 10;
```

## Comparing two runs (did the optimisation help?)

Every run writes a JSON scorecard.

From the terminal UI it is easy to see if the changes made any noticable improvement or not.
