import http from 'k6/http';
import { check } from 'k6';
import { Counter } from 'k6/metrics';

// ============================================================================
// Metrics & Counters
// ============================================================================
const lockSuccessCounter = new Counter('lock_success');
const lockConflictCounter = new Counter('lock_conflict');
const unexpectedStatusCounter = new Counter('unexpected_status');

// ============================================================================
// Configuration
// ============================================================================
const VUS = parseInt(__ENV.VUS || '200', 10);
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    concurrent_seat_race: {
      executor: 'per-vu-iterations',
      vus: VUS,
      iterations: 1,
      maxDuration: '30s',
    },
  },
  thresholds: {
    // Assert exactly 1 request receives a lock success (201 Created or 200 OK)
    'lock_success': ['count==1'],
    // Assert all remaining (VUS - 1) concurrent requests receive 409 Conflict
    'lock_conflict': [`count==${VUS - 1}`],
    // Assert ZERO unexpected statuses (500 internal server error, 401 unauthorized, etc.)
    'unexpected_status': ['count==0'],
  },
  setupTimeout: '180s',
};

// ============================================================================
// Setup Phase: Executed once before concurrent VUs are spawned
// ============================================================================
export function setup() {
  console.log(`[k6 Setup] Connecting to target backend: ${BASE_URL}`);
  console.log(`[k6 Setup] Simulating ${VUS} concurrent virtual users for the exact same seat...`);

  const jsonHeaders = { 'Content-Type': 'application/json' };

  // 1. Authenticate as Admin (bootstrap fallback if needed)
  let adminToken = '';
  let loginRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
    username: 'admin',
    password: 'admin123',
  }), { headers: jsonHeaders });

  if (loginRes.status === 200) {
    adminToken = loginRes.json('token');
  } else {
    // Register admin if first-time run
    http.post(`${BASE_URL}/api/v1/auth/register`, JSON.stringify({
      username: 'admin',
      password: 'admin123',
      role: 'ROLE_ADMIN',
    }), { headers: jsonHeaders });

    loginRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
      username: 'admin',
      password: 'admin123',
    }), { headers: jsonHeaders });
    adminToken = loginRes.json('token');
  }

  if (!adminToken) {
    throw new Error(`Failed to obtain Admin token: status=${loginRes.status}, body=${loginRes.body}`);
  }

  // 2. Admin API: Create a dedicated event with seats for this concurrency race
  const eventPayload = JSON.stringify({
    title: `k6 Concurrency Test Event ${Date.now()}`,
    venue: 'Load Test Stadium',
    eventTime: '2029-01-01T20:00:00Z',
    seatGenerators: [
      {
        row: 'A',
        startNumber: 1,
        endNumber: 10,
        category: 'VIP',
        price: 150.00,
      },
    ],
  });

  const eventRes = http.post(`${BASE_URL}/api/v1/events`, eventPayload, {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${adminToken}`,
    },
  });

  if (eventRes.status !== 201) {
    throw new Error(`Failed to create event: status=${eventRes.status}, body=${eventRes.body}`);
  }

  const eventId = eventRes.json('id');
  console.log(`[k6 Setup] Created test event ID: ${eventId}`);

  // 3. Fetch public seat map to select the target seat (Seat A1)
  const seatMapRes = http.get(`${BASE_URL}/api/v1/events/${eventId}/seats`);
  if (seatMapRes.status !== 200) {
    throw new Error(`Failed to fetch seat map: status=${seatMapRes.status}`);
  }

  const seats = seatMapRes.json('seats');
  if (!seats || seats.length === 0) {
    throw new Error('No seats found in created event');
  }

  const targetSeat = seats[0];
  const targetSeatId = targetSeat.id;
  const targetSeatNumber = targetSeat.seatNumber;
  console.log(`[k6 Setup] Target Contested Seat: ${targetSeatNumber} (ID: ${targetSeatId})`);

  // 4. Pre-register / authenticate user tokens for concurrency testing
  const poolSize = Math.min(VUS, 50);
  console.log(`[k6 Setup] Provisioning tokens for ${poolSize} distinct user accounts...`);
  const tokens = [];

  for (let i = 1; i <= poolSize; i++) {
    const username = `k6_runner_${Date.now()}_${i}`;
    const registerRes = http.post(`${BASE_URL}/api/v1/auth/register`, JSON.stringify({
      username: username,
      password: 'Password123!',
      role: 'ROLE_USER',
    }), { headers: jsonHeaders });

    if (registerRes.status === 201 || registerRes.status === 200) {
      tokens.push(registerRes.json('token'));
    } else {
      // Fallback: login default seeded user
      const defaultUserRes = http.post(`${BASE_URL}/api/v1/auth/login`, JSON.stringify({
        username: 'user',
        password: 'user123',
      }), { headers: jsonHeaders });
      tokens.push(defaultUserRes.json('token'));
    }
  }

  console.log(`[k6 Setup] Provisioned ${tokens.length} tokens. Starting simultaneous race test with ${VUS} VUs!`);

  return {
    baseUrl: BASE_URL,
    eventId: eventId,
    targetSeatId: targetSeatId,
    targetSeatNumber: targetSeatNumber,
    tokens: tokens,
    totalVUs: VUS,
  };
}

// ============================================================================
// Virtual User Race Function: Executed simultaneously by all 100+ VUs
// ============================================================================
export default function (data) {
  const vuIndex = (__VU - 1) % data.tokens.length;
  const token = data.tokens[vuIndex];

  // Each concurrent virtual user passes a unique Idempotency-Key (must be <= 64 chars)
  const shortSeatId = data.targetSeatId.split('-')[0];
  const idempotencyKey = `k6-race-${shortSeatId}-vu${__VU}-${Date.now()}`;

  const payload = JSON.stringify({
    eventId: data.eventId,
    seatIds: [data.targetSeatId],
  });

  const params = {
    headers: {
      'Content-Type': 'application/json',
      'Authorization': `Bearer ${token}`,
      'Idempotency-Key': idempotencyKey,
    },
    timeout: '15s',
  };

  // Simultaneous atomic POST /api/v1/bookings/lock for the exact same seat ID
  const res = http.post(`${data.baseUrl}/api/v1/bookings/lock`, payload, params);

  if (res.status === 201 || res.status === 200) {
    lockSuccessCounter.add(1);
    console.log(`[SUCCESS] VU ${__VU} successfully acquired lock for seat ${data.targetSeatNumber}! Status: ${res.status}`);
  } else if (res.status === 409) {
    lockConflictCounter.add(1);
  } else {
    unexpectedStatusCounter.add(1);
    console.error(`[UNEXPECTED] VU ${__VU} received HTTP ${res.status}: ${res.body}`);
  }

  check(res, {
    'expected status (201 Success or 409 Conflict)': (r) => r.status === 201 || r.status === 200 || r.status === 409,
  });
}

// ============================================================================
// Summary Handler: Outputs formatted concurrency verification report
// ============================================================================
export function handleSummary(data) {
  const successCount = data.metrics.lock_success ? data.metrics.lock_success.values.count : 0;
  const conflictCount = data.metrics.lock_conflict ? data.metrics.lock_conflict.values.count : 0;
  const unexpectedCount = data.metrics.unexpected_status ? data.metrics.unexpected_status.values.count : 0;
  const total = successCount + conflictCount + unexpectedCount;

  const passed = successCount === 1 && conflictCount === (VUS - 1) && unexpectedCount === 0;

  const summary = [
    '',
    '='.repeat(68),
    '   HIGH-CONCURRENCY SEAT RESERVATION RACE TEST SUMMARY',
    '='.repeat(68),
    `  Total Concurrent Requests:     ${total}`,
    `  Expected VUs:                  ${VUS}`,
    `  Successful Locks (201/200):    ${successCount} (Required: 1)`,
    `  Conflicts Prevented (409):     ${conflictCount} (Required: ${VUS - 1})`,
    `  Unexpected Status Codes:       ${unexpectedCount} (Required: 0)`,
    '-'.repeat(68),
    `  Zero Double-Booking Guarantee: ${passed ? 'PASSED [OK]' : 'FAILED [VIOLATION]'}`,
    '='.repeat(68),
    ''
  ].join('\n');

  return {
    stdout: summary,
  };
}
