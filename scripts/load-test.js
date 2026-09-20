/*
 * SPDX-License-Identifier: Apache-2.0
 * Copyright 2026 IT Beratung Hermann GmbH
 */

// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 IT Beratung Hermann GmbH
//
// Load test of the render endpoint (k6). Run through `mise run load-test`
// against a running service:
//
//   BASE_URL=http://localhost:8080 mise run load-test
//
// The rate rises in steps, so an autoscaler has time to react. A 503 with
// Retry-After is a correct answer of an instance whose queue is full, not an
// error; it is counted separately.
//
// A second scenario samples /q/metrics, so a breach of render_time can be read:
// a full queue (server_render_queue at its capacity) means the service is
// saturated and wants more instances; an empty one means rendering itself got
// slower, which is a defect, not a scaling question.

import http from 'k6/http';
import { check } from 'k6';
import { Counter, Trend } from 'k6/metrics';

const BASE_URL = __ENV.BASE_URL || 'http://127.0.0.1:8080';
const TEMPLATE = __ENV.TEMPLATE || 'demo';
const FORMAT = __ENV.FORMAT || 'pdf';
const data = open(__ENV.DATA || '../demo/data-email.json');

const rejected = new Counter('renders_rejected');
const renderTime = new Trend('render_time', true);
const serverQueue = new Trend('server_render_queue');
const serverActive = new Trend('server_render_active');

export const options = {
  scenarios: {
    render: {
      executor: 'ramping-arrival-rate',
      startRate: Number(__ENV.START_RATE || 2),
      timeUnit: '1s',
      preAllocatedVUs: 10,
      maxVUs: Number(__ENV.MAX_VUS || 100),
      stages: [
        { target: Number(__ENV.START_RATE || 2), duration: __ENV.WARM || '30s' },
        { target: Number(__ENV.PEAK_RATE || 20), duration: __ENV.RAMP || '2m' },
        { target: Number(__ENV.PEAK_RATE || 20), duration: __ENV.HOLD || '2m' },
        { target: 0, duration: '30s' },
      ],
    },
    // Reads the server's own saturation while the load runs.
    probe: {
      executor: 'constant-arrival-rate',
      rate: 1,
      timeUnit: '5s',
      duration: __ENV.PROBE_DURATION || '5m30s',
      preAllocatedVUs: 1,
      exec: 'probe',
    },
  },
  thresholds: {
    // Every answer is either a document or an honest 503; nothing else.
    checks: ['rate == 1.0'],
    render_time: ['p(95) < 5000'],
  },
};

/** One value of a Prometheus gauge, summed over its label sets. */
function gauge(body, name) {
  let sum = null;
  for (const line of body.split('\n')) {
    if (line.startsWith('#') || !line.startsWith(name)) {
      continue;
    }
    const rest = line.slice(name.length);
    if (rest.length && rest[0] !== ' ' && rest[0] !== '{') {
      continue;
    }
    const value = Number(line.slice(line.lastIndexOf(' ') + 1));
    if (!Number.isNaN(value)) {
      sum = (sum || 0) + value;
    }
  }
  return sum;
}

export function probe() {
  const response = http.get(`${BASE_URL}/q/metrics`, { timeout: '10s' });
  if (response.status !== 200) {
    return;
  }
  const queued = gauge(response.body, 'executor_queued_tasks');
  const active = gauge(response.body, 'executor_active_threads');
  if (queued !== null) {
    serverQueue.add(queued);
  }
  if (active !== null) {
    serverActive.add(active);
  }
}

export default function () {
  const response = http.post(`${BASE_URL}/templates/${TEMPLATE}/render?format=${FORMAT}`, data, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '60s',
  });
  if (response.status === 503) {
    rejected.add(1);
    check(response, { 'overload answers Retry-After': (r) => r.headers['Retry-After'] !== undefined });
    return;
  }
  renderTime.add(response.timings.duration);
  check(response, {
    'renders a document': (r) => r.status === 200 && r.body.length > 1000,
  });
}
