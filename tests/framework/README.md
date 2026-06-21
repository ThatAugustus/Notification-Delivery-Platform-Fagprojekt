# NDP Test Framework — correctness gate + signpost

One command that drives load through the whole pipeline, waits for it to drain,
and **proves the system's guarantees** — then tells you *where to look* when one
breaks.

```bash
# default run (reads config.json)
python3 tests/framework/ndp_test.py

# quick overrides
python3 tests/framework/ndp_test.py --pattern spike --rate 300 --duration 60
python3 tests/framework/ndp_test.py --chaos          # enable faults from config.json
python3 tests/framework/ndp_test.py --no-teardown    # keep test tenants to inspect
```

Needs `python3`, `k6`, `docker` (the compose stack must be up and the app on :8080).
No pip installs — stdlib only.

## What it does (5 phases)

1. **Pre-flight** — app health `UP`, **zero backlog** (else it waits, then aborts),
   captures a DB-clock baseline.
2. **Provision** — creates dedicated test tenants (`ndp-test-<runid>-*`) + API keys,
   so the run is isolated from other dev traffic. Soft-deleted at the end (data kept).
3. **Load** — k6 sends unique messages (`constant | ramp | spike`); optional chaos
   pauses/stops a container mid-run.
4. **Drain** — polls until every message reaches a terminal state, or times out
   (a timeout is itself a loss bug → FAIL).
5. **Conservation + scorecard** — proves the invariants, prints a verdict, and saves
   `tests/performance/results/framework-<runid>.json`.

## The invariants it proves

| Invariant | Meaning |
|---|---|
| no silent loss | every submitted message reached DELIVERED/FAILED |
| ingest accounted | API-accepted count == rows persisted |
| unique content | no two messages collided (load generator sanity) |
| outbox drained | nothing stuck unpublished |
| no duplicate delivery (attempts) | ≤1 SUCCESS per notification in `delivery_attempts` |
| no duplicate delivery (mailpit) | **ground truth** — no extra emails reached recipients |

The two duplicate checks differ on purpose: the *attempts* check can be masked by
app-side dedup, so the *mailpit* check is what proves nothing escaped to a real inbox.

## Reading the result

- **VERDICT: PASS/FAIL** is the gate.
- On failure, each `[FAIL]` prints `→` **signposts** to the exact Grafana panel or SQL
  to debug, plus diagnostics (e.g. duplicate offenders split by `retry_count` and
  channel — which localises outbox/redelivery vs. retry-logic bugs).

## Scope & honesty (read before quoting numbers)

This is a **gate** (correctness) and a **signpost** (where to look) — not a benchmark.
The throughput line is **indicative, not conclusive**: a single laptop run is noisy.

For report-grade performance claims you still need (deliberately *not* in this tool yet):
- repeated runs with variance, at multiple load levels
- per-stage latency attribution (requires stage-timestamp instrumentation)
- a dedicated multi-tenant fairness experiment

Use this to catch regressions and find *where* to dig; use Grafana +
`pg_stat_statements` to find *why*.

### pg_statements_guide

``` bash
# Connect to postgres docker container in terminal
docker exec -it ndp-postgres psql -U dev -d notification_platform

# to exit psql:
/q
```

```sql
-- Check if pg_stat_statements is enabled
SELECT extname
FROM pg_extension
WHERE extname = 'pg_stat_statements';

-- Show the most expensive queries
SELECT calls, total_exec_time, mean_exec_time, query
FROM pg_stat_statements
ORDER BY total_exec_time DESC
LIMIT 10;
```


## Comparing two runs (did my optimization help?)

Every run writes a JSON scorecard. Diff two of them:

```bash
python3 - <<'PY'
import json, glob
a, b = sorted(glob.glob("tests/performance/results/framework-*.json"))[-2:]
A, B = json.load(open(a)), json.load(open(b))
print("verdict ", A["verdict"], "→", B["verdict"])
print("drain_s ", A["drain_seconds"], "→", B["drain_seconds"])
print("delivered", A["metrics"]["delivered"], "→", B["metrics"]["delivered"])
PY
```
(A proper `compare` command can be added once the team agrees on the metrics that matter.)
