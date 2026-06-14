import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

// ── Custom Metrics ──
const notificationsAccepted = new Counter('notifications_accepted');
const notificationsRejected = new Counter('notifications_rejected');
const apiLatency = new Trend('api_latency', true); // true = time values

// ── Test Configuration ──
// Ramp up gradually, hold, then ramp down.
// This simulates realistic traffic patterns.
export const options = {
  stages: [
    { duration: '10s', target: 10 },   // Warm up: 0 → 10 users over 10s
    { duration: '30s', target: 10 },   // Hold: 10 users for 30s (steady state)
    { duration: '10s', target: 30 },   // Spike: 10 → 30 users over 10s
    { duration: '30s', target: 30 },   // Hold: 30 users for 30s
    { duration: '10s', target: 0 },    // Cool down: 30 → 0 users
  ],
  thresholds: {
    http_req_duration: ['p(95)<500'],   // 95% of requests must be under 500ms
    http_req_failed: ['rate<0.01'],     // Less than 1% error rate
  },
};

const API_KEY = 'my-test-key-123';
const BASE_URL = 'http://localhost:8080';

export default function () {
  // Each virtual user sends one notification per iteration
  const uniqueId = `k6-${__VU}-${__ITER}-${Date.now()}`;

  const payload = JSON.stringify({
    channel: 'EMAIL',
    recipient: `loadtest-vu${__VU}@example.com`,
    subject: `Load Test VU${__VU} Iter${__ITER}`,
    content: `This is a load test notification from virtual user ${__VU}, iteration ${__ITER}.`,
    idempotencyKey: uniqueId,
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'X-API-Key': API_KEY,
    },
  };

  const res = http.post(`${BASE_URL}/api/v1/notifications`, payload, params);

  // Track custom metrics
  apiLatency.add(res.timings.duration);

  const success = check(res, {
    'status is 202': (r) => r.status === 202,
    'has notification id': (r) => JSON.parse(r.body).id !== undefined,
  });

  if (success) {
    notificationsAccepted.add(1);
  } else {
    notificationsRejected.add(1);
    console.error(`FAILED: status=${res.status} body=${res.body}`);
  }

  // Small pause between requests per user (simulates realistic client behavior)
  sleep(0.1);
}

// ── Save results as JSON for comparison ──
// k6 automatically calls this when the test ends.
// Results are saved to tests/performance/results/ with a timestamp.
export function handleSummary(data) {
  const timestamp = new Date().toISOString().replace(/[:.]/g, '-').slice(0, 19);
  const filename = `tests/performance/results/run-${timestamp}.json`;

  // Extract the key metrics we care about
  const summary = {
    timestamp: new Date().toISOString(),

    // Hardware fingerprint — so we know which machine produced these numbers
    environment: {
      hostname: __ENV.HOSTNAME || __ENV.USER || 'unknown',
      k6_version: 'k6', // k6 doesn't expose its version in JS, but the CLI output shows it
      os: __ENV.OS_INFO || 'see run-load-test.sh output',
      cpu: __ENV.CPU_INFO || 'see run-load-test.sh output',
      ram_gb: __ENV.RAM_GB || 'see run-load-test.sh output',
      note: __ENV.TEST_NOTE || '',
    },

    duration_seconds: data.state.testRunDurationMs / 1000,
    vus_max: data.metrics.vus_max ? data.metrics.vus_max.values.max : 0,
    throughput_rps: data.metrics.http_reqs ? data.metrics.http_reqs.values.rate : 0,
    latency: {
      avg_ms: data.metrics.http_req_duration ? data.metrics.http_req_duration.values.avg : 0,
      p95_ms: data.metrics.http_req_duration ? data.metrics.http_req_duration.values['p(95)'] : 0,
      max_ms: data.metrics.http_req_duration ? data.metrics.http_req_duration.values.max : 0,
    },
    error_rate: data.metrics.http_req_failed ? data.metrics.http_req_failed.values.rate : 0,
    total_requests: data.metrics.http_reqs ? data.metrics.http_reqs.values.count : 0,
    notifications_accepted: data.metrics.notifications_accepted ? data.metrics.notifications_accepted.values.count : 0,
    notifications_rejected: data.metrics.notifications_rejected ? data.metrics.notifications_rejected.values.count : 0,
  };

  return {
    'stdout': textSummary(data, { indent: ' ', enableColors: true }),
    [filename]: JSON.stringify(summary, null, 2),
  };
}

// k6 built-in text summary
import { textSummary } from 'https://jslib.k6.io/k6-summary/0.0.1/index.js';
