-- ============================================================================
-- High-Concurrency Ticket Booking Engine - V2 Users Schema
-- ============================================================================

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    username VARCHAR(100) NOT NULL,
    password VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'ROLE_USER',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_users_username UNIQUE(username)
);

CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
