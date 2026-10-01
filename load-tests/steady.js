// Steady traffic (design §14, scenario 1): create -> authorised -> capture -> captured, with
// mock-psp settling at once. Used for the JFR recording of #34.
import http from 'k6/http';
import { check, sleep } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE = __ENV.BASE_URL || 'http://payment-service:8080';
const KEY = __ENV.API_KEY;
const RATE = Number(__ENV.RATE || 50);

export const options = {
  scenarios: {
    steady: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 100,
      maxVUs: 1000,
      stages: [
        { target: RATE, duration: __ENV.RAMP || '1m' },
        { target: RATE, duration: __ENV.HOLD || '4m' },
      ],
    },
  },
  summaryTrendStats: ['avg', 'med', 'p(90)', 'p(99)', 'max'],
};

const toAuthorised = new Trend('time_to_authorised', true);
const toCaptured = new Trend('time_to_captured', true);
const incomplete = new Counter('incomplete_payments');

export default function () {
  const auth = { Authorization: `Bearer ${KEY}`, 'Content-Type': 'application/json' };
  const ref = `k6-${__VU}-${__ITER}-${Date.now()}`;

  const created = http.post(
    `${BASE}/v1/payments`,
    JSON.stringify({ merchantReference: ref, amountMinor: 10000, currency: 'GBP' }),
    { headers: { ...auth, 'Idempotency-Key': ref }, tags: { name: 'create' } });
  if (!check(created, { 'create 202': (r) => r.status === 202 })) {
    incomplete.add(1);
    return;
  }
  const id = created.json('paymentId');

  let started = Date.now();
  if (!waitFor(id, 'AUTHORIZED', auth)) {
    incomplete.add(1);
    return;
  }
  toAuthorised.add(Date.now() - started);

  const captured = http.post(`${BASE}/v1/payments/${id}/capture`, null, {
    headers: { ...auth, 'Idempotency-Key': `${ref}-capture` },
    tags: { name: 'capture' },
  });
  if (!check(captured, { 'capture 202': (r) => r.status === 202 })) {
    incomplete.add(1);
    return;
  }
  started = Date.now();
  if (!waitFor(id, 'CAPTURED', auth)) {
    incomplete.add(1);
    return;
  }
  toCaptured.add(Date.now() - started);
}

function waitFor(id, status, headers) {
  for (let i = 0; i < 150; i++) {
    const r = http.get(`${BASE}/v1/payments/${id}`, { headers, tags: { name: 'poll' } });
    if (r.status === 200 && r.json('status') === status) {
      return true;
    }
    sleep(0.2);
  }
  return false;
}
