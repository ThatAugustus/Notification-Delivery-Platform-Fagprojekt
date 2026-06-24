// ═══════════════════════════════════════════════════════════════════
//  NDP load generator (driven by ndp_test.py — do not run k6 directly)
//
//  Reads its whole configuration from two env vars set by the
//  orchestrator:
//    NDP_CFG     – {base_url, run_id, rate, duration, pattern, channels, webhook_url}
//    NDP_TARGETS – [{name, apiKey}]   (one per provisioned test tenant)
//    NDP_SUMMARY_OUT – file path the machine-readable summary is written to
//
//  Every message is globally unique (run_id + VU + ITER baked into the
//  recipient / subject / content) so the conservation checker can prove
//  uniqueness and trace any single message end-to-end.
// ═══════════════════════════════════════════════════════════════════
import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';

const accepted = new Counter('notifications_accepted');
const rejected = new Counter('notifications_rejected');
const apiLatency = new Trend('api_latency', true);

// Chaos: malformed requests. Each must be REJECTED (4xx) and never persisted/delivered.
const badSubmitted = new Counter('bad_submitted');
const badRejected = new Counter('bad_rejected');   // correctly refused (non-2xx)
const badAccepted = new Counter('bad_accepted');   // wrongly let in (2xx) — a validation bug

const CFG = JSON.parse(__ENV.NDP_CFG);
const TARGETS = JSON.parse(__ENV.NDP_TARGETS);

// ── Load-pattern algorithms ────────────────────────────────────────
// Each returns a k6 scenario object. Edit the numbers here to reshape
// a pattern; you should not need to touch anything else.
function constantPattern(rate, dur) {
  return {
    executor: 'constant-arrival-rate',
    rate: rate, timeUnit: '1s', duration: `${dur}s`,
    preAllocatedVUs: Math.max(20, Math.ceil(rate / 2)),
    maxVUs: Math.max(50, rate * 4),
  };
}

function rampPattern(rate, dur) {
  // ramp up over the first third, hold, ramp down over the last third.
  const third = Math.max(1, Math.floor(dur / 3));
  const low = Math.max(1, Math.ceil(rate * 0.1));
  return {
    executor: 'ramping-arrival-rate',
    startRate: low, timeUnit: '1s',
    preAllocatedVUs: Math.max(20, Math.ceil(rate / 2)),
    maxVUs: Math.max(50, rate * 4),
    stages: [
      { target: rate, duration: `${third}s` },
      { target: rate, duration: `${dur - 2 * third}s` },
      { target: low, duration: `${third}s` },
    ],
  };
}

function spikePattern(rate, dur) {
  // steady baseline, then a sudden 2x spike for 5s, then back to baseline.
  const quarter = Math.max(1, Math.floor(dur / 4));
  const base = Math.max(1, Math.ceil(rate * 0.25));
  const tail = Math.max(1, dur - 2 * quarter - 5);
  return {
    executor: 'ramping-arrival-rate',
    startRate: base, timeUnit: '1s',
    preAllocatedVUs: Math.max(20, Math.ceil(rate / 2)),
    maxVUs: Math.max(50, rate * 6),
    stages: [
      { target: base, duration: `${quarter}s` },
      { target: rate * 2, duration: '5s' },   // ← the spike
      { target: rate * 2, duration: `${quarter}s` },
      { target: base, duration: `${tail}s` },
    ],
  };
}

function buildScenario() {
  const r = CFG.rate, d = CFG.duration;
  let s;
  if (CFG.pattern === 'constant') s = constantPattern(r, d);
  else if (CFG.pattern === 'spike') s = spikePattern(r, d);
  else s = rampPattern(r, d);
  s.exec = 'send';
  return s;
}

export const options = {
  scenarios: { ndp: buildScenario() },
};

// Weighted tenant pool: a tenant with weight 3 appears 3x, so it gets ~3x the
// share of the total rate. Equal weights = even split.
const TENANT_POOL = (() => {
  const pool = [];
  for (const t of TARGETS) {
    const w = Math.max(1, t.weight || 1);
    for (let i = 0; i < w; i++) pool.push(t);
  }
  return pool;
})();

// Send one deliberately-malformed request. All three variants are rejected
// BEFORE any persistence (validation / auth / parsing), so a correct system
// never creates a row or delivers anything for them.
function sendBad(t, uid) {
  badSubmitted.add(1);
  const goodHdr = { 'Content-Type': 'application/json', 'X-API-Key': t.apiKey };
  let body, headers;
  switch ((__VU + __ITER) % 3) {
    case 0:  // missing required fields → 400 (@Valid)
      body = JSON.stringify({ channel: 'EMAIL', subject: 'NDP-BAD' });
      headers = goodHdr; break;
    case 1:  // malformed JSON → 400 (message converter)
      body = '{ not valid json ';
      headers = goodHdr; break;
    default: // valid body, invalid API key → 401 (auth)
      body = JSON.stringify({ channel: 'EMAIL', recipient: `${uid}@ndp-test.local`,
                              content: 'x', idempotencyKey: uid });
      headers = { 'Content-Type': 'application/json', 'X-API-Key': 'ndp-invalid-key' };
  }
  const res = http.post(`${CFG.base_url}/api/v1/notifications`, body, { headers });
  if (res.status === 202) badAccepted.add(1);   // garbage got in — a bug
  else badRejected.add(1);                       // correctly refused
}

export function send() {
  const t = TENANT_POOL[(__VU + __ITER) % TENANT_POOL.length];
  const uid = `${CFG.run_id}-${__VU}-${__ITER}`;

  if (CFG.bad_fraction > 0 && Math.random() < CFG.bad_fraction) {
    sendBad(t, uid);
    return;
  }

  const channel = CFG.channels[(__VU + __ITER) % CFG.channels.length];

  const payload = {
    channel: channel,
    recipient: `${uid}@ndp-test.local`,
    content: `NDP test message ${uid}`,
    subject: `NDP-TEST-${CFG.run_id}-${__VU}-${__ITER}`,
    idempotencyKey: uid,
  };
  if (channel === 'WEBHOOK') payload.webhookUrl = CFG.webhook_url;

  const res = http.post(`${CFG.base_url}/api/v1/notifications`, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json', 'X-API-Key': t.apiKey },
  });

  apiLatency.add(res.timings.duration);
  if (check(res, { 'status is 202': (r) => r.status === 202 })) {
    accepted.add(1);
  } else {
    rejected.add(1);
  }
}

export function handleSummary(data) {
  const m = data.metrics;
  const summary = {
    submitted: m.notifications_accepted ? m.notifications_accepted.values.count : 0,
    rejected: m.notifications_rejected ? m.notifications_rejected.values.count : 0,
    bad_submitted: m.bad_submitted ? m.bad_submitted.values.count : 0,
    bad_rejected: m.bad_rejected ? m.bad_rejected.values.count : 0,
    bad_accepted: m.bad_accepted ? m.bad_accepted.values.count : 0,
    submit_rate: m.http_reqs ? m.http_reqs.values.rate : 0,
    api_avg_ms: m.http_req_duration ? m.http_req_duration.values.avg : 0,
    api_p95_ms: m.http_req_duration ? m.http_req_duration.values['p(95)'] : 0,
    api_max_ms: m.http_req_duration ? m.http_req_duration.values.max : 0,
    http_error_rate: m.http_req_failed ? m.http_req_failed.values.rate : 0,
    duration_s: data.state.testRunDurationMs / 1000,
  };
  const out = { stdout: textSummary(data, { indent: '  ', enableColors: true }) };
  if (__ENV.NDP_SUMMARY_OUT) out[__ENV.NDP_SUMMARY_OUT] = JSON.stringify(summary, null, 2);
  return out;
}
