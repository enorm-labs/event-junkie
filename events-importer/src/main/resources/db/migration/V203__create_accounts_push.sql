-- V203 — Web Push (#3894, ADR-055): one row per browser that turned notifications on, and the point up to which new
-- events have been announced.
--
-- A subscription belongs to one browser, so an account has one row per device. The endpoint is the push service's
-- URL for that browser and is unique across accounts: the same browser signed in to another account moves the row.
-- `p256dh` and `auth` are the browser's keys for the payload encryption (RFC 8291), base64url as the browser hands them.
--
-- The cursor is one row, as `accounts.sign_up` is (V200). It starts at the migration's time, so the first run
-- announces nothing that existed before. The BFF writes it, so the default privileges from V193 stay.
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004).

CREATE TABLE accounts.push_subscription
(
    id           UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    account_id   UUID        NOT NULL REFERENCES accounts.account (id) ON DELETE CASCADE,
    endpoint     TEXT        NOT NULL UNIQUE,
    p256dh       TEXT        NOT NULL,
    auth         TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    last_sent_at TIMESTAMPTZ
);

CREATE INDEX push_subscription_account_idx ON accounts.push_subscription (account_id);

CREATE TABLE accounts.push_cursor
(
    id         BOOLEAN PRIMARY KEY  DEFAULT true CHECK (id),
    sent_until TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO accounts.push_cursor DEFAULT VALUES;
