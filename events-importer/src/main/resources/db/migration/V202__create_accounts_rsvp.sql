-- V202 — an account's "interested" or "going" per event (#3892). Private: only the account reads it.
--
-- No foreign key into `events`, as for follows (V195): `bff` may only read there, and an importer
-- delete must not fail on an RSVP. One whose event is gone drops out of every read by the join, and
-- the BFF's daily cleanup deletes it. Past events stay in `events`, so the history keeps working.
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004). `bff` gets its privileges from V193's
-- default privileges on `accounts`.

CREATE TABLE accounts.rsvp
(
    account_id UUID        NOT NULL REFERENCES accounts.account (id) ON DELETE CASCADE,
    event_id   BIGINT      NOT NULL,
    status     TEXT        NOT NULL CHECK (status IN ('interested', 'going')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, event_id)
);

CREATE INDEX rsvp_event_idx ON accounts.rsvp (event_id);
