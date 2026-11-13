# High-Concurrency Ticket Booking Engine - Architecture & Context

## 1. System Overview & Objectives
- **Goal:** Build a production-grade transactional backend engine handling high-concurrency seat reservations with zero double-booking guarantees.
- **Core Principles:** Strict consistency, distributed locking, idempotent payment webhooks, and graceful failure recovery under 512MB RAM free-tier constraints (Koyeb/Render + Neon/Supabase PostgreSQL + Upstash Redis).

## 2. Tech Stack & Engineering Constraints
- **Language & Framework:** Java 21, Spring Boot 3.4+.
- **JVM Flags:** `JAVA_TOOL_OPTIONS="-Xmx320m -Xms200m -XX:MaxMetaspaceSize=96m -XX:+UseG1GC"`
- **Database (PostgreSQL 16):** HikariCP connection pool settings: `maximumPoolSize=5`, `minimumIdle=1`, `connectionTimeout=10000` (to handle free-tier cold starts).
- **Cache & Distributed Lock (Redis):** Spring Data Redis / Redisson with TLS (`rediss://`) enabled.
- **Security:** Stateless Spring Security + JWT filter (no heavy server-side session storage).

## 3. Database Schema (PostgreSQL)
Ensure migrations or JPA entities match this schema precisely:
1. `events`: id (UUID, PK), title (VARCHAR), venue (VARCHAR), event_time (TIMESTAMPTZ), total_seats (INT), created_at.
2. `seats`: id (UUID, PK), event_id (UUID, FK), seat_number (VARCHAR, e.g., A1), category (VARCHAR), price (DECIMAL), status (VARCHAR: AVAILABLE, LOCKED, BOOKED), locked_by_user_id (UUID), lock_expires_at (TIMESTAMPTZ), version (INT, for @Version optimistic locking), UNIQUE(event_id, seat_number).
3. `bookings`: id (UUID, PK), user_id (UUID), event_id (UUID, FK), total_amount (DECIMAL), status (VARCHAR: PENDING, CONFIRMED, CANCELLED, EXPIRED), idempotency_key (VARCHAR(64), UNIQUE), expires_at (TIMESTAMPTZ), created_at.
4. `booking_seats`: booking_id (UUID, FK), seat_id (UUID, FK), PRIMARY KEY (booking_id, seat_id).

## 4. Dual-Layer Concurrency & Locking Strategy
- **Layer 1 (Fast Gatekeeper - Redis):** Distributed lock using `SET key value NX PX 10000` for each seat before touching the database.
- **Layer 2 (Source of Truth - Postgres):** Inside a transactional block, fetch seats using pessimistic locking (`SELECT ... FOR UPDATE`) or optimistic versioning to guarantee strict consistency.
- **Multi-Seat Deadlock Prevention:** Before acquiring locks, the backend MUST sort all requested `seatIds` in ascending UUID order to prevent circular wait deadlocks.
- **Idempotency:** Enforce `Idempotency-Key` header on all booking and payment confirmation APIs to prevent duplicate processing.

## 5. Expiration & Edge-Case Handling Rules
- **Hybrid Expiration:** 
  - Lazy Check: If a seat's `lock_expires_at < NOW()`, treat it as AVAILABLE.
  - Active Sweeper: `@Scheduled` background job running every 30 seconds to transition expired `LOCKED` bookings to `EXPIRED` and release seats back to `AVAILABLE`.
- **Payment at 10:01 Boundary (Race Condition):** If a payment webhook arrives after the sweeper has already released the seat to another user, DO NOT overwrite the new user's lock. Mark the late booking as `REFUND_INITIATED`.
