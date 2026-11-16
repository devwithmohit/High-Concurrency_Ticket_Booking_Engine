# High-Concurrency Ticket Booking Engine

A production-grade transactional backend engine designed to handle high-concurrency seat reservations with a **strict zero double-booking guarantee**, distributed locking, idempotent webhooks, and automatic expired lock cleanup—tuned for 512MB RAM resource constraints.

---

## Architecture Overview

```
                      [ Client Requests (100+ Concurrent VUs) ]
                                         │
                                         ▼
                      ┌──────────────────────────────────────┐
                      │    Spring Boot 3.4+ REST Engine      │
                      │  (Stateless JWT, HikariCP maxPool=5) │
                      └──────────────────┬───────────────────┘
                                         │
                 ┌───────────────────────┴───────────────────────┐
                 │                                               │
                 ▼                                               ▼
   ┌───────────────────────────┐                   ┌───────────────────────────┐
   │  Layer 1: Fast Gatekeeper │                   │  Layer 2: Source of Truth │
   │           Redis           │                   │         PostgreSQL        │
   │  SET lock:seat:{id} NX PX │                   │    SELECT ... FOR UPDATE  │
   │  Instant O(1) Rejection   │                   │   Strict ACID Transaction │
   └───────────────────────────┘                   └───────────────────────────┘
```

1. **Layer 1 (Fast Gatekeeper - Redis):** Evaluates `SET lock:seat:{id} <owner> NX PX 10000` to absorb and reject concurrent requests in sub-milliseconds before database connection acquisition.
2. **Layer 2 (Source of Truth - PostgreSQL 16):** Inside a transactional boundary, acquires pessimistic write locks (`SELECT ... FOR UPDATE ORDER BY s.id ASC`) on sorted seat UUIDs to eliminate circular-wait deadlocks and guarantee strict consistency.
3. **Lazy & Active Expiration Sweeper:**
   - **Lazy check:** Seats marked `LOCKED` whose `lock_expires_at < NOW()` are immediately treated as `AVAILABLE`.
   - **Active sweeper:** Background `@Scheduled` job running every 30 seconds transitions expired bookings to `EXPIRED`, restores seats to `AVAILABLE`, and evicts Redis locks and cached seat maps.
4. **Boundary Race Condition (10:01 Race):** If a payment webhook arrives after a seat lock expired or was re-allocated to another customer, the engine **never overwrites** the active lock. Instead, the transaction records the booking as `REFUND_INITIATED`.

---

## Tech Stack & Constraints

- **Language & Runtime:** Java 21 LTS
- **Framework:** Spring Boot 3.4.3
- **Database:** PostgreSQL 16 (HikariCP pool: `maxPoolSize=5`, `minimumIdle=1`, `connectionTimeout=10000ms`)
- **Caching & Locks:** Redis 7 / Redisson / Spring Data Redis (TLS `rediss://` supported)
- **Container Memory Flags:** `JAVA_TOOL_OPTIONS="-Xmx320m -Xms200m -XX:MaxMetaspaceSize=96m -XX:+UseG1GC"`
- **Load Testing:** Grafana k6

---

## Quickstart with Docker Compose

### 1. Spin up Database and Redis Infrastructure

```bash
docker-compose up -d postgres redis
```

Health checks automatically verify readiness:
- PostgreSQL: `pg_isready -U postgres -d ticket_engine`
- Redis: `redis-cli ping`

### 2. Run the Spring Boot Application

Either run natively:
```bash
./mvnw spring-boot:run
```
Or start all services (including containerized app) via Docker Compose:
```bash
docker-compose up -d --build app
```

Verify service health:
```bash
curl http://localhost:8080/actuator/health
```

---

## High-Concurrency Load Testing with k6

The repository includes a dedicated k6 test script ([load-test.js](file:///d:/High-Concurrency_Ticket_Booking_Engine/load-test.js)) that simulates **100+ concurrent virtual users** hitting `POST /api/v1/bookings/lock` simultaneously for the **exact same seat ID**.

### Strict Zero Double-Booking Assertions

The test validates three core assertions via k6 thresholds:
- **`lock_success` (`count == 1`):** Exactly **1** request successfully acquires the seat lock (`201 Created` or `200 OK`).
- **`lock_conflict` (`count == VUS - 1`):** All remaining **99** concurrent requests receive `409 Conflict`.
- **`unexpected_status` (`count == 0`):** Zero unhandled errors, deadlocks, or `500 Internal Server Errors`.

If even one duplicate booking occurs or a deadlock is encountered, the k6 test **fails immediately**.

---

### Running the Test Inside Docker

#### Method A: Using Docker Compose Profile (Recommended)

The [docker-compose.yml](file:///d:/High-Concurrency_Ticket_Booking_Engine/docker-compose.yml) includes a pre-configured `k6` service linked to the internal Docker network.

1. Ensure the backend application and databases are running:
   ```bash
   docker-compose up -d postgres redis app
   ```

2. Execute the concurrent load test container:
   ```bash
   docker-compose run --rm k6
   ```

3. To customize the number of concurrent virtual users (e.g. 150 VUs):
   ```bash
   docker-compose run --rm -e VUS=150 k6
   ```

#### Method B: Standalone `docker run` Against Docker Compose Network

Run k6 using the official `grafana/k6` Docker image attached to the project network:

```bash
docker run --rm -i \
  --network high-concurrency_ticket_booking_engine_default \
  -e BASE_URL=http://app:8080 \
  -e VUS=100 \
  -v ${PWD}/load-test.js:/load-test.js \
  grafana/k6:latest run /load-test.js
```

*(On Windows PowerShell, use `${PWD}:/load-test.js` or standard absolute path).*

#### Method C: Running Against Host Backend from Docker

If running Spring Boot locally on your host machine (outside Docker) on port `8080`:

**Linux:**
```bash
docker run --rm -i \
  --network host \
  -e BASE_URL=http://localhost:8080 \
  -e VUS=100 \
  -v ${PWD}/load-test.js:/load-test.js \
  grafana/k6:latest run /load-test.js
```

**macOS / Windows:**
```bash
docker run --rm -i \
  --add-host=host.docker.internal:host-gateway \
  -e BASE_URL=http://host.docker.internal:8080 \
  -e VUS=100 \
  -v ${PWD}/load-test.js:/load-test.js \
  grafana/k6:latest run /load-test.js
```

---

### Expected Test Output

Upon executing the test, k6 outputs the concurrency verification report:

```
====================================================================
   HIGH-CONCURRENCY SEAT RESERVATION RACE TEST SUMMARY
====================================================================
  Total Concurrent Requests:     100
  Expected VUs:                  100
  Successful Locks (201/200):    1 (Required: 1)
  Conflicts Prevented (409):     99 (Required: 99)
  Unexpected Status Codes:       0 (Required: 0)
--------------------------------------------------------------------
  Zero Double-Booking Guarantee: PASSED [OK]
====================================================================

running (00m09.2s), 000/100 VUs, 100 complete and 0 interrupted iterations
concurrent_seat_race ✓ [======================================] 100 VUs  00m01.1s/30s  100/100 iters

  ✓ expected status (201 Success or 409 Conflict)

  ✓ lock_conflict...............: count=99  (threshold: count==99)
  ✓ lock_success................: count=1   (threshold: count==1)
  ✓ unexpected_status...........: count=0   (threshold: count==0)
```

---

## API Reference Summary

| Method | Endpoint | Auth | Description |
|---|---|---|---|
| `POST` | `/api/v1/auth/login` | Public | Authenticates user and returns JWT |
| `POST` | `/api/v1/auth/register` | Public | Registers a new user account |
| `GET` | `/api/v1/events` | Public | Lists all events |
| `POST` | `/api/v1/events` | `ROLE_ADMIN` | Creates event and bulk generates seats |
| `GET` | `/api/v1/events/{id}/seats` | Public | Live Redis-cached seat map (with lazy lock check) |
| `POST` | `/api/v1/bookings/lock` | User (`Bearer`) | **Two-Phase Reservation:** Redis gatekeeper + Postgres pessimistic lock (`Idempotency-Key` required) |
| `POST` | `/api/v1/webhooks/payment` | Public / Gateway | Finalizes booking (`CONFIRMED`) or triggers `REFUND_INITIATED` on 10:01 boundary race |
| `GET` | `/actuator/health` | Public | Probes PostgreSQL & Redis health |
