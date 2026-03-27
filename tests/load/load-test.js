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
