-- ============================================================================
-- High-Concurrency Ticket Booking Engine - V1 Initial Schema
-- ============================================================================

-- 1. Events Table
CREATE TABLE IF NOT EXISTS events (
    id UUID PRIMARY KEY,
    title VARCHAR(255) NOT NULL,
    venue VARCHAR(255) NOT NULL,
    event_time TIMESTAMPTZ NOT NULL,
    total_seats INT NOT NULL CHECK (total_seats > 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- 2. Seats Table
CREATE TABLE IF NOT EXISTS seats (
    id UUID PRIMARY KEY,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE CASCADE,
    seat_number VARCHAR(50) NOT NULL,
    category VARCHAR(50) NOT NULL,
    price NUMERIC(10, 2) NOT NULL CHECK (price >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE', 'LOCKED', 'BOOKED')),
    locked_by_user_id UUID,
    lock_expires_at TIMESTAMPTZ,
    version INT NOT NULL DEFAULT 0,
    CONSTRAINT uk_seats_event_seat_number UNIQUE(event_id, seat_number)
);

-- Indexing for Seat lookup & lock sweeper query
CREATE INDEX IF NOT EXISTS idx_seats_event_id ON seats(event_id);
CREATE INDEX IF NOT EXISTS idx_seats_event_status ON seats(event_id, status);
CREATE INDEX IF NOT EXISTS idx_seats_status_expires ON seats(status, lock_expires_at);

-- 3. Bookings Table
CREATE TABLE IF NOT EXISTS bookings (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    event_id UUID NOT NULL REFERENCES events(id) ON DELETE RESTRICT,
    total_amount NUMERIC(10, 2) NOT NULL CHECK (total_amount >= 0),
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED', 'EXPIRED', 'REFUND_INITIATED')),
    idempotency_key VARCHAR(64) NOT NULL,
    expires_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_bookings_idempotency_key UNIQUE(idempotency_key)
);

-- Indexing for Bookings
CREATE INDEX IF NOT EXISTS idx_bookings_user_id ON bookings(user_id);
CREATE INDEX IF NOT EXISTS idx_bookings_event_id ON bookings(event_id);
CREATE INDEX IF NOT EXISTS idx_bookings_status_expires ON bookings(status, expires_at);

-- 4. Booking Seats (Join Table with Composite Primary Key)
CREATE TABLE IF NOT EXISTS booking_seats (
    booking_id UUID NOT NULL REFERENCES bookings(id) ON DELETE CASCADE,
    seat_id UUID NOT NULL REFERENCES seats(id) ON DELETE RESTRICT,
    PRIMARY KEY (booking_id, seat_id)
);

CREATE INDEX IF NOT EXISTS idx_booking_seats_seat_id ON booking_seats(seat_id);
