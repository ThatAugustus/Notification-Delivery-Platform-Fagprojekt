#!/usr/bin/env python3
"""
NDP test framework — correctness gate + signpost.

One command that:
  1. checks the system is clean and healthy before starting
  2. provisions dedicated test tenants (soft-deleted afterwards)
  3. drives a configurable k6 load (constant | ramp | spike), optionally with chaos
  4. waits for the pipeline to drain
  5. proves the conservation laws (no loss, no duplication, content uniqueness)
  6. on any failure, points you at the exact dashboard panel / query to debug

Scope of this tool (read this before trusting a number):
  - It is a *gate* (correctness verdict) and a *signpost* (where to look).
  - Throughput here is INDICATIVE, not conclusive — a single laptop run is noisy.
    For report-grade performance claims, run it several times and compare spreads,
    and read per-stage detail in Grafana. This tool tells you THAT something is
    slow/broken and roughly where; the dashboard tells you exactly why.

Stdlib only — no pip installs. Needs: python3, k6, docker.
"""

import argparse
import json
import os
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from datetime import datetime, timezone

HERE = os.path.dirname(os.path.abspath(__file__))
REPO = os.path.abspath(os.path.join(HERE, "..", ".."))
RESULTS_DIR = os.path.join(REPO, "tests", "performance", "results")

NON_TERMINAL = ("ACCEPTED", "QUEUED", "PROCESSING", "RETRY_SCHEDULED")

# ── tiny ANSI helpers ──────────────────────────────────────────────
G, R, Y, C, DIM, B, NC = (
    "\033[0;32m", "\033[0;31m", "\033[1;33m", "\033[0;36m",
    "\033[2m", "\033[1m", "\033[0m",
)
def c(s, col): return f"{col}{s}{NC}"
def hr(): print(C + "─" * 67 + NC)
def head(s): print("\n" + C + "═" * 67 + NC + f"\n{B}  {s}{NC}\n" + C + "═" * 67 + NC)


# ════════════════════════════════════════════════════════════════════
#  low-level helpers
# ════════════════════════════════════════════════════════════════════
class Ctx:
    """Everything the run needs, loaded from config + overrides."""
    def __init__(self, cfg):
        self.base_url = cfg["base_url"].rstrip("/")
        self.admin_key = cfg["admin_key"]
        self.mailpit_url = cfg["mailpit_url"].rstrip("/")
        self.db_container = cfg["db_container"]
        self.db_user = cfg["db_user"]
        self.db_name = cfg["db_name"]
        self.load = cfg["load"]
        self.chaos = cfg["chaos"]
        self.limits = cfg["limits"]


def run(cmd, timeout=None, check=False):
    p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if check and p.returncode != 0:
        raise RuntimeError(f"command failed: {' '.join(cmd)}\n{p.stderr}")
    return p


def psql(ctx, sql):
    """Run SQL inside the postgres container. Returns list[list[str]] (| separated)."""
    p = run([
        "docker", "exec", ctx.db_container,
        "psql", "-U", ctx.db_user, ctx.db_name,
        "-t", "-A", "-F", "|", "-c", sql,
    ], timeout=30)
    if p.returncode != 0:
        raise RuntimeError(f"psql failed: {p.stderr.strip()}")
    rows = []
    for line in p.stdout.splitlines():
        line = line.strip()
        if line:
            rows.append(line.split("|"))
    return rows


def psql_int(ctx, sql):
    rows = psql(ctx, sql)
    if not rows or not rows[0] or rows[0][0] == "":
        return 0
    try:
        return int(rows[0][0])
    except ValueError:
        return 0


def http_json(method, url, headers=None, body=None, timeout=30):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, method=method, headers=headers or {})
    if data is not None:
        req.add_header("Content-Type", "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as r:
            raw = r.read().decode()
            return r.status, (json.loads(raw) if raw else None)
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        try:
            return e.code, json.loads(raw)
        except Exception:
            return e.code, raw


def mailpit_count(ctx, run_id):
    """How many emails mailpit actually received for THIS run (ground truth).

    NB: in mailpit's search response, `total` is the whole-mailbox count — the
    query-matching count is `messages_count`. Using `total` would count every
    other run's emails too.
    """
    q = urllib.parse.quote(f"NDP-TEST-{run_id}")
    url = f"{ctx.mailpit_url}/api/v1/search?query={q}&limit=1"
    try:
        status, data = http_json("GET", url)
        if status == 200 and isinstance(data, dict):
            return int(data.get("messages_count", 0))
    except Exception:
        pass
    return 0


# ════════════════════════════════════════════════════════════════════
#  phases
# ════════════════════════════════════════════════════════════════════
def environment():
    def sh(cmd):
        try:
            return run(cmd, timeout=5).stdout.strip()
        except Exception:
            return "?"
    cpu = sh(["sysctl", "-n", "machdep.cpu.brand_string"]) or sh(["uname", "-p"])
    try:
        pages = os.sysconf("SC_PHYS_PAGES"); psize = os.sysconf("SC_PAGE_SIZE")
        ram = f"{round(pages * psize / (1024 ** 3), 1)} GB"
    except Exception:
        ram = "?"
    osinfo = sh(["sw_vers", "-productVersion"]) or sh(["uname", "-sr"])
    host = sh(["hostname", "-s"])
    return {"host": host, "cpu": cpu, "ram": ram, "os": osinfo}


def preflight(ctx):
    head("PHASE 1 · Pre-flight")

    # health
    status, data = http_json("GET", f"{ctx.base_url}/actuator/health")
    health = (data or {}).get("status") if isinstance(data, dict) else None
    if health != "UP":
        print(c(f"  ✗ app health is {health!r} (HTTP {status}) — is the app running?", R))
        sys.exit(1)
    print(c("  ✓ health UP", G))

    # backlog must be clean, otherwise old in-flight work pollutes the verdict
    nt_sql = ("SELECT COUNT(*) FROM notifications WHERE status IN "
              f"({', '.join(repr(s) for s in NON_TERMINAL)})")
    unpub_sql = "SELECT COUNT(*) FROM outbox_events WHERE published = false"

    nt = psql_int(ctx, nt_sql)
    unpub = psql_int(ctx, unpub_sql)
    if nt or unpub:
        print(c(f"  … backlog present (non-terminal={nt}, unpublished={unpub}); waiting 10s…", Y))
        time.sleep(10)
        nt = psql_int(ctx, nt_sql)
        unpub = psql_int(ctx, unpub_sql)
        if nt or unpub:
            print(c(f"  ✗ system not clean (non-terminal={nt}, unpublished={unpub}). "
                    "Let it drain, then re-run.", R))
            sys.exit(1)
    print(c("  ✓ no backlog (clean start)", G))

    baseline = psql(ctx, "SELECT now()")[0][0]
    print(f"  baseline (DB clock): {c(baseline, Y)}")
    return baseline


def provision_keys(ctx, run_id):
    """Mint a fresh API key on each configured (worker-backed) tenant.

    We deliberately do NOT create new tenants: per-tenant queues + workers are
    registered only at app startup, so a runtime-created tenant has no consumer
    and its messages get stuck. So we reuse existing tenants and just add a
    throwaway key, revoked at teardown. Run isolation comes from the baseline
    timestamp + the unique run_id in every message.
    """
    head("PHASE 2 · Provision API keys (on existing worker-backed tenants)")
    hdr = {"X-Admin-Key": ctx.admin_key}
    targets = []
    for tid in ctx.load["tenant_ids"]:
        st, t = http_json("GET", f"{ctx.base_url}/api/v1/admin/tenants/{tid}", hdr)
        if st != 200:
            print(c(f"  ✗ tenant {tid} not found/active (HTTP {st}). It must exist and have "
                    "been present at app startup so its workers are registered.", R))
            sys.exit(1)
        st, k = http_json("POST", f"{ctx.base_url}/api/v1/admin/tenants/{tid}/api-keys",
                          hdr, {"name": f"ndp-test-{run_id}"})
        if st != 201:
            print(c(f"  ✗ failed to mint api key for {tid}: HTTP {st} {k}", R))
            sys.exit(1)
        targets.append({"name": t.get("name", tid), "id": tid,
                        "apiKey": k["rawKey"], "keyId": k["id"]})
        print(c(f"  ✓ {t.get('name', tid)}  ({tid})", G))
    return targets


def teardown_keys(ctx, targets):
    hdr = {"X-Admin-Key": ctx.admin_key}
    for t in targets:
        if t.get("keyId"):
            http_json("DELETE", f"{ctx.base_url}/api/v1/admin/tenants/{t['id']}/api-keys/{t['keyId']}", hdr)
    print(c(f"  ✓ revoked {len(targets)} throwaway test key(s); tenants untouched", DIM))


def chaos_worker(ctx, fault, events, start_monotonic):
    """Inject one fault at its scheduled offset, then heal it."""
    at = fault.get("at_seconds", 0)
    dur = fault.get("duration_seconds", 10)
    cont = fault["container"]
    action = fault.get("action", "pause")
    label = fault.get("label", action)

    delay = at - (time.monotonic() - start_monotonic)
    if delay > 0:
        time.sleep(delay)

    down = {"pause": ["docker", "pause", cont], "stop": ["docker", "stop", cont]}
    up = {"pause": ["docker", "unpause", cont], "stop": ["docker", "start", cont]}
    cmd_down = down.get(action, down["pause"])
    cmd_up = up.get(action, up["pause"])

    t0 = datetime.now(timezone.utc).strftime("%H:%M:%S")
    run(cmd_down)
    print(c(f"  ⚡ chaos[{label}]: {action} {cont} @ {t0} (for {dur}s)", Y))
    time.sleep(dur)
    run(cmd_up)
    t1 = datetime.now(timezone.utc).strftime("%H:%M:%S")
    print(c(f"  ⚡ chaos[{label}]: healed {cont} @ {t1}", Y))
    events.append({"label": label, "container": cont, "action": action,
                   "down_at": t0, "up_at": t1, "duration_s": dur})


def run_load(ctx, run_id, targets):
    head("PHASE 3 · Load" + (" + chaos" if ctx.chaos.get("enabled") else ""))
    cfg = {
        "base_url": ctx.base_url,
        "run_id": run_id,
        "rate": ctx.load["rate"],
        "duration": ctx.load["duration_seconds"],
        "pattern": ctx.load["pattern"],
        "channels": ctx.load["channels"],
        "webhook_url": ctx.load.get("webhook_url", ""),
    }
    print(f"  pattern={c(cfg['pattern'], Y)} rate={c(cfg['rate'], Y)}/s "
          f"duration={c(cfg['duration'], Y)}s channels={cfg['channels']} "
          f"tenants={len(targets)}\n")

    summary_out = os.path.join(HERE, f".summary-{run_id}.json")
    env = dict(os.environ)
    env["NDP_CFG"] = json.dumps(cfg)
    env["NDP_TARGETS"] = json.dumps([{"name": t["name"], "apiKey": t["apiKey"]} for t in targets])
    env["NDP_SUMMARY_OUT"] = summary_out

    events = []
    threads = []
    start = time.monotonic()
    if ctx.chaos.get("enabled"):
        for fault in ctx.chaos.get("faults", []):
            th = threading.Thread(target=chaos_worker, args=(ctx, fault, events, start), daemon=True)
            th.start(); threads.append(th)

    subprocess.run(["k6", "run", os.path.join(HERE, "loadgen.js")], env=env)
    for th in threads:
        th.join(timeout=60)

    k6 = {}
    if os.path.exists(summary_out):
        with open(summary_out) as f:
            k6 = json.load(f)
        os.remove(summary_out)
    return k6, events, start


def delivery_profile(ctx, jscope, window=5):
    """Reconstruct the true delivery timeline from delivery_attempts.attempted_at.

    Unlike polling the DELIVERED counter during the drain phase (which can't see
    deliveries that happened during the load phase, and lags because status flips
    late), this uses the actual recorded delivery timestamps — so it covers the
    WHOLE run at 1-second resolution.

    Returns:
      peak_rate    – max sustained msgs/s over any `window`-second span
      active_span  – wall seconds from first to last successful delivery
      per_second   – [[second_offset, cumulative_delivered], ...] for plotting
    """
    rows = psql(ctx, f"""
        SELECT EXTRACT(EPOCH FROM date_trunc('second', d.attempted_at))::bigint AS sec,
               COUNT(*)
        FROM delivery_attempts d JOIN notifications n ON d.notification_id = n.id
        WHERE {jscope} AND d.status = 'SUCCESS'
        GROUP BY sec ORDER BY sec""")
    if not rows:
        return {"peak_rate": 0.0, "active_span": 0, "per_second": []}
    counts = {int(r[0]): int(r[1]) for r in rows}
    lo, hi = min(counts), max(counts)
    dense = [counts.get(s, 0) for s in range(lo, hi + 1)]  # fill idle seconds with 0

    w = min(window, len(dense))
    run = sum(dense[:w])
    best = run
    for i in range(w, len(dense)):
        run += dense[i] - dense[i - w]
        best = max(best, run)
    peak_rate = best / w if w else 0.0

    per_second, cum = [], 0
    for i, v in enumerate(dense):
        cum += v
        per_second.append([i, cum])
    return {"peak_rate": round(peak_rate, 1), "active_span": hi - lo + 1, "per_second": per_second}


def scope_clause(tids, baseline, alias=""):
    p = f"{alias}." if alias else ""
    ids = ", ".join(repr(t) for t in tids)
    return f"{p}tenant_id IN ({ids}) AND {p}created_at >= '{baseline}'"


def drain(ctx, baseline, tids, load_start):
    head("PHASE 4 · Drain")
    scope = scope_clause(tids, baseline)
    jscope = scope_clause(tids, baseline, alias="n")
    nt_in = ", ".join(repr(s) for s in NON_TERMINAL)
    timeout = ctx.limits["drain_timeout_seconds"]
    poll = ctx.limits.get("drain_poll_seconds", 3)
    stall_limit = ctx.limits.get("stall_seconds", 60)
    waited = 0
    total = psql_int(ctx, f"SELECT COUNT(*) FROM notifications WHERE {scope}")
    if total == 0:
        print(c("  ✗ nothing was persisted — load produced no notifications (check k6 output above)", R))
        return 0, True, []
    stalled = 0
    samples = []  # (elapsed_s, delivered) — used to derive PEAK sustained throughput
    while waited < timeout:
        inflight = psql_int(ctx, f"SELECT COUNT(*) FROM notifications WHERE {scope} AND status IN ({nt_in})")
        delivered = psql_int(ctx, f"SELECT COUNT(*) FROM notifications WHERE {scope} AND status = 'DELIVERED'")
        failed = psql_int(ctx, f"SELECT COUNT(*) FROM notifications WHERE {scope} AND status = 'FAILED'")
        attempts = psql_int(ctx, f"SELECT COUNT(*) FROM delivery_attempts d JOIN notifications n ON d.notification_id=n.id WHERE {jscope}")
        elapsed = time.monotonic() - load_start
        samples.append((elapsed, delivered))
        inst = (samples[-1][1] - samples[-2][1]) / (samples[-1][0] - samples[-2][0]) if len(samples) > 1 and samples[-1][0] > samples[-2][0] else 0
        print(f"\r  in-flight {c(f'{inflight:>6}', Y)}  delivered {c(f'{delivered:>6}', G)}"
              f"  now ~{inst:5.0f}/s  ({elapsed:4.0f}s since load start)   ", end="", flush=True)
        if inflight == 0:
            print()
            drained_at = int(time.monotonic() - load_start)
            print(c(f"  ✓ drained in ~{drained_at}s", G))
            return drained_at, False, samples
        # "Stuck" = the pipeline produced absolutely NOTHING — zero delivered, failed,
        # or even attempted — for a sustained window. This catches a dead-from-start
        # pipeline (e.g. a queue with no consumer) without ever false-failing a healthy
        # one: this app legitimately pauses for long stretches between redelivery waves,
        # so "unchanged" is not stuck — only "never did anything" is.
        alive = delivered + failed + attempts
        stalled = stalled + poll if alive == 0 else 0
        if stalled >= stall_limit:
            print()
            print(c(f"  ✗ STUCK — {inflight} in-flight, zero pipeline activity for {stalled}s "
                    "(nothing delivered/attempted at all; e.g. no consumer for their queue)", R))
            return waited, True, samples
        time.sleep(poll)
        waited += poll
    print()
    print(c(f"  ✗ DRAIN TIMEOUT after {timeout}s — messages stuck (this is a loss bug)", R))
    return waited, True, samples


# ════════════════════════════════════════════════════════════════════
#  conservation + diagnostics
# ════════════════════════════════════════════════════════════════════
def conservation(ctx, run_id, tids, baseline, k6, drain_timed_out):
    scope = scope_clause(tids, baseline)
    jscope = scope_clause(tids, baseline, alias="n")
    nt_in = ", ".join(repr(s) for s in NON_TERMINAL)

    submitted = int(k6.get("submitted", 0))
    rejected = int(k6.get("rejected", 0))
    accepted_db = psql_int(ctx, f"SELECT COUNT(*) FROM notifications WHERE {scope}")
    distinct_subject = psql_int(ctx, f"SELECT COUNT(DISTINCT subject) FROM notifications WHERE {scope}")
    delivered = psql_int(ctx, f"SELECT COUNT(*) FROM notifications WHERE {scope} AND status='DELIVERED'")
    failed = psql_int(ctx, f"SELECT COUNT(*) FROM notifications WHERE {scope} AND status='FAILED'")
    nonterminal = psql_int(ctx, f"SELECT COUNT(*) FROM notifications WHERE {scope} AND status IN ({nt_in})")
    email_total = psql_int(ctx, f"SELECT COUNT(*) FROM notifications WHERE {scope} AND channel='EMAIL'")
    email_delivered = psql_int(ctx, f"SELECT COUNT(*) FROM notifications WHERE {scope} AND channel='EMAIL' AND status='DELIVERED'")

    outbox_pub = psql_int(ctx, f"SELECT COUNT(*) FROM outbox_events o JOIN notifications n ON o.notification_id=n.id WHERE {jscope} AND o.published=true")
    outbox_unpub = psql_int(ctx, f"SELECT COUNT(*) FROM outbox_events o JOIN notifications n ON o.notification_id=n.id WHERE {jscope} AND o.published=false")

    da_success = psql_int(ctx, f"SELECT COUNT(*) FROM delivery_attempts d JOIN notifications n ON d.notification_id=n.id WHERE {jscope} AND d.status='SUCCESS'")
    da_fail = psql_int(ctx, f"SELECT COUNT(*) FROM delivery_attempts d JOIN notifications n ON d.notification_id=n.id WHERE {jscope} AND d.status='FAILED'")

    dup_a = psql_int(ctx, f"""
        SELECT COALESCE(SUM(cnt-1),0) FROM (
          SELECT d.notification_id, COUNT(*) cnt
          FROM delivery_attempts d JOIN notifications n ON d.notification_id=n.id
          WHERE {jscope} AND d.status='SUCCESS'
          GROUP BY d.notification_id HAVING COUNT(*)>1
        ) x""")

    prof = delivery_profile(ctx, jscope)

    mailpit = mailpit_count(ctx, run_id)
    # Ground truth: every message is unique, so the number of distinct emails that
    # *should* exist is the count of EMAIL notifications submitted. More than that in
    # mailpit == real duplicates escaped. (Compare against submitted, NOT against the
    # DELIVERED-status count, which lags while redelivery cycles settle.)
    dup_b = max(0, mailpit - email_total)

    m = dict(submitted=submitted, rejected=rejected, accepted_db=accepted_db,
             distinct_subject=distinct_subject, delivered=delivered, failed=failed,
             nonterminal=nonterminal, email_total=email_total, email_delivered=email_delivered,
             outbox_pub=outbox_pub, outbox_unpub=outbox_unpub,
             da_success=da_success, da_fail=da_fail, dup_a=dup_a, dup_b=dup_b,
             mailpit=mailpit,
             peak_rate=prof["peak_rate"], active_span=prof["active_span"],
             delivery_per_second=prof["per_second"])

    # ── invariants: (name, ok, detail) ──
    inv = []
    inv.append(("no silent loss", nonterminal == 0 and not drain_timed_out,
                f"{nonterminal} stuck non-terminal" if (nonterminal or drain_timed_out) else "all reached terminal"))
    inv.append(("ingest accounted", submitted == accepted_db,
                f"submitted {submitted} vs persisted {accepted_db}"))
    inv.append(("unique mail content", distinct_subject == accepted_db,
                f"{distinct_subject} distinct / {accepted_db} rows"))
    inv.append(("outbox drained", outbox_unpub == 0,
                f"{outbox_unpub} unpublished"))
    inv.append(("no duplicate delivery (attempts)", dup_a == 0,
                f"{dup_a} extra SUCCESS rows" +
                (f"  ({dup_a/delivered*100:.1f}%)" if delivered else "")))
    inv.append(("no duplicate delivery (mailpit/escaped)", dup_b == 0,
                f"{dup_b} extra emails vs {email_total} unique" if dup_b
                else f"mailpit {mailpit} = {email_total} unique emails"))

    diagnostics = {}
    if dup_a > 0:
        diagnostics["duplicates"] = diagnose_duplicates(ctx, jscope)

    if drain_timed_out or nonterminal > 0:
        rows = psql(ctx, f"SELECT status, COUNT(*) FROM notifications WHERE {scope} AND status IN ({nt_in}) GROUP BY status ORDER BY 2 DESC")
        diagnostics["stuck"] = [(r[0], int(r[1])) for r in rows]

    return m, inv, diagnostics


def diagnose_duplicates(ctx, jscope):
    offenders = f"""(SELECT d.notification_id FROM delivery_attempts d JOIN notifications n ON d.notification_id=n.id
                     WHERE {jscope} AND d.status='SUCCESS' GROUP BY d.notification_id HAVING COUNT(*)>1)"""
    by_retry = psql(ctx, f"""
        SELECT n.retry_count, COUNT(*) FROM notifications n
        WHERE n.id IN {offenders} GROUP BY n.retry_count ORDER BY n.retry_count""")
    by_channel = psql(ctx, f"""
        SELECT n.channel, COUNT(*) FROM notifications n
        WHERE n.id IN {offenders} GROUP BY n.channel ORDER BY 2 DESC""")
    samples = psql(ctx, f"SELECT n.id FROM notifications n WHERE n.id IN {offenders} LIMIT 5")
    return {
        "by_retry_count": [(r[0], int(r[1])) for r in by_retry],
        "by_channel": [(r[0], int(r[1])) for r in by_channel],
        "sample_ids": [r[0] for r in samples],
    }


# ── signpost: each failed invariant → where to look next ────────────
SIGNPOSTS = {
    "no silent loss": [
        "Grafana → 'queue messages ready/unacked' around the run window (stuck where?)",
        "diagnostics 'stuck' below shows which status they're frozen in",
    ],
    "outbox drained": [
        "Grafana → 'Scheduled Task: Outbox' execution rate — is the poller running?",
        "SQL: SELECT last_error, COUNT(*) FROM outbox_events WHERE published=false GROUP BY 1",
    ],
    "no duplicate delivery (attempts)": [
        "diagnostics below: retry_count split tells outbox/redelivery vs retry-logic",
        "Grafana → 'RabbitMQ unacked' spike = redelivery; trace a sample id's delivery_attempts timeline",
    ],
    "no duplicate delivery (mailpit/escaped)": [
        "These reached real recipients — app-side dedup did NOT catch them",
        "Cross-check a sample id: SELECT * FROM delivery_attempts WHERE notification_id='…' ORDER BY attempted_at",
    ],
    "ingest accounted": [
        "submitted>persisted ⇒ 429 rate-limit or validation rejects (check k6 rejected + app logs)",
    ],
    "unique content": [
        "Duplicate subjects in DB ⇒ load generator collision (should never happen) — inspect notifications",
    ],
}


# ════════════════════════════════════════════════════════════════════
#  scorecard
# ════════════════════════════════════════════════════════════════════
def scorecard(env, run_id, baseline, ctx, k6, events, drain_s, drain_timed_out,
              m, inv, diagnostics, load_start, drain_end, samples):
    head("SCORECARD")
    failed = [name for name, ok, _ in inv if not ok]
    verdict_pass = not failed

    print(f"  {B}Environment{NC}  {env['cpu']} · {env['ram']} · {env['os']} · {env['host']}")
    print(f"  {B}Run id{NC}       {run_id}")
    print(f"  {B}Baseline{NC}     {baseline}")
    ld = ctx.load
    print(f"  {B}Load{NC}         {ld['rate']}/s · {ld['pattern']} · {ld['duration_seconds']}s · "
          f"{'+'.join(ld['channels'])} · {len(ld['tenant_ids'])} tenant(s)")
    if events:
        for e in events:
            print(f"  {B}Chaos{NC}        {e['label']}: {e['action']} {e['container']} {e['down_at']}–{e['up_at']}")
    else:
        print(f"  {B}Chaos{NC}        none")

    hr()
    print(f"  {B}Pipeline conservation{NC}")
    def line(lbl, val, extra=""):
        print(f"    {lbl:<32}{c(str(val), Y):>0} {extra}")
    pct = lambda n, d: f"({n/d*100:.2f}%)" if d else ""
    line("Submitted (202)", m["submitted"], f"rejected {m['rejected']}")
    line("Persisted (notifications)", m["accepted_db"], pct(m["accepted_db"], m["submitted"]))
    line("Outbox published", m["outbox_pub"], pct(m["outbox_pub"], m["accepted_db"]))
    line("Delivered", m["delivered"], pct(m["delivered"], m["accepted_db"]))
    line("Delivery attempts", f"SUCCESS {m['da_success']} · FAIL {m['da_fail']}")
    line("Mailpit received", m["mailpit"])
    line("Drain time", f"{drain_s}s" + (c("  TIMEOUT", R) if drain_timed_out else ""))

    hr()
    print(f"  {B}Throughput{NC}  {DIM}(indicative — not report-grade on a single run){NC}")
    peak = m.get("peak_rate", 0)
    span = m.get("active_span", 0)
    avg_active = m["delivered"] / span if span else 0
    submit_rate = f"{k6.get('submit_rate', 0):.0f}/s"
    api_p95 = f"{k6.get('api_p95_ms', 0):.0f}ms"
    print(f"    Submit rate    {c(submit_rate, Y)}    API p95 {api_p95}")
    print(f"    Peak delivery  {c(f'{peak:.0f}/s', Y)}    {DIM}(max sustained over ~5s, from delivery timestamps){NC}")
    print(f"    Avg delivery   {c(f'{avg_active:.0f}/s', Y)}    {DIM}({m['delivered']} delivered over {span}s of actual delivery){NC}")
    print(f"    Time to drain  {c(f'{drain_s}s', Y)}    {DIM}(wall clock incl. idle waits + stale-cleanup tail){NC}")

    hr()
    print(f"  {B}Correctness{NC}")
    for name, ok, detail in inv:
        tag = c("[PASS]", G) if ok else c("[FAIL]", R)
        print(f"    {tag} {name:<38} {DIM}{detail}{NC}")
        if not ok and name in SIGNPOSTS:
            for hint in SIGNPOSTS[name]:
                print(f"           {c('→', C)} {hint}")

    if "duplicates" in diagnostics:
        d = diagnostics["duplicates"]
        print(f"\n  {B}▼ diagnostics: duplicate delivery{NC}")
        print(f"    by retry_count: " + "  ".join(f"{rc}→{n}" for rc, n in d["by_retry_count"]))
        print(f"    by channel:     " + "  ".join(f"{ch}→{n}" for ch, n in d["by_channel"]))
        zero = next((n for rc, n in d["by_retry_count"] if rc == "0"), 0)
        tot = sum(n for _, n in d["by_retry_count"]) or 1
        if zero / tot > 0.5:
            print(c("    → most offenders have retry_count=0 ⇒ outbox double-publish / MQ redelivery, NOT retry logic", C))
        else:
            print(c("    → offenders concentrated in retried messages ⇒ look at retry re-queue path", C))
        print(f"    sample ids:     {', '.join(d['sample_ids'])}")

    if "stuck" in diagnostics:
        print(f"\n  {B}▼ diagnostics: stuck messages{NC}")
        print(f"    " + "  ".join(f"{s}→{n}" for s, n in diagnostics["stuck"]))

    hr()
    if verdict_pass:
        print(f"  VERDICT: {c('PASS', G)}  (all {len(inv)} invariants held)")
    else:
        print(f"  VERDICT: {c('FAIL', R)}  ({len(failed)} invariant(s): {', '.join(failed)})")
    print(C + "═" * 67 + NC)
    return verdict_pass


def save_json(run_id, env, baseline, ctx, k6, events, drain_s, drain_timed_out, m, inv, diagnostics, verdict_pass, samples):
    os.makedirs(RESULTS_DIR, exist_ok=True)
    path = os.path.join(RESULTS_DIR, f"framework-{run_id}.json")
    out = {
        "run_id": run_id,
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "environment": env,
        "baseline_db_clock": baseline,
        "config": {"load": ctx.load, "chaos": ctx.chaos, "limits": ctx.limits},
        "k6": k6,
        "chaos_events": events,
        "drain_seconds": drain_s,
        "drain_timed_out": drain_timed_out,
        "peak_delivery_rate": m.get("peak_rate"),
        "active_delivery_span_s": m.get("active_span"),
        "metrics": m,
        "invariants": [{"name": n, "pass": ok, "detail": d} for n, ok, d in inv],
        "diagnostics": diagnostics,
        "verdict": "PASS" if verdict_pass else "FAIL",
    }
    with open(path, "w") as f:
        json.dump(out, f, indent=2)
    print(f"\n  {DIM}saved → {os.path.relpath(path, REPO)}{NC}")
    return path


# ════════════════════════════════════════════════════════════════════
#  main
# ════════════════════════════════════════════════════════════════════
def main():
    ap = argparse.ArgumentParser(description="NDP correctness gate + signpost")
    ap.add_argument("--config", default=os.path.join(HERE, "config.json"))
    ap.add_argument("--pattern", choices=["constant", "ramp", "spike"])
    ap.add_argument("--rate", type=int)
    ap.add_argument("--duration", type=int)
    ap.add_argument("--chaos", action="store_true", help="enable chaos faults from config")
    ap.add_argument("--no-teardown", action="store_true", help="keep test tenants after the run")
    args = ap.parse_args()

    with open(args.config) as f:
        cfg = json.load(f)
    if args.pattern: cfg["load"]["pattern"] = args.pattern
    if args.rate: cfg["load"]["rate"] = args.rate
    if args.duration: cfg["load"]["duration_seconds"] = args.duration
    if args.chaos: cfg["chaos"]["enabled"] = True

    ctx = Ctx(cfg)
    run_id = datetime.now().strftime("%Y%m%d-%H%M%S")
    env = environment()

    head("NDP TEST FRAMEWORK · correctness gate + signpost")
    print(f"  {env['cpu']} · {env['ram']} · {env['os']}")

    baseline = preflight(ctx)
    targets = provision_keys(ctx, run_id)
    tids = list(dict.fromkeys(t["id"] for t in targets))

    verdict_pass = False
    try:
        k6, events, load_start = run_load(ctx, run_id, targets)
        drain_s, timed_out, samples = drain(ctx, baseline, tids, load_start)
        drain_end = time.monotonic()
        m, inv, diagnostics = conservation(ctx, run_id, tids, baseline, k6, timed_out)
        verdict_pass = scorecard(env, run_id, baseline, ctx, k6, events, drain_s, timed_out,
                                 m, inv, diagnostics, load_start, drain_end, samples)
        save_json(run_id, env, baseline, ctx, k6, events, drain_s, timed_out, m, inv, diagnostics, verdict_pass, samples)
    finally:
        head("PHASE 5 · Teardown")
        if args.no_teardown:
            print(c("  --no-teardown: leaving throwaway keys in place", DIM))
        else:
            teardown_keys(ctx, targets)

    sys.exit(0 if verdict_pass else 1)


if __name__ == "__main__":
    main()
