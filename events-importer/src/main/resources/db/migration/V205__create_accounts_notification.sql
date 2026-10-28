-- V205 — what is new for an account, shown under the header's bell until it is read (#4078).
--
-- One row per account, event and kind. `about_kind` and `about_id` name the follow that made it, so the entry says
-- whose new date it is. No foreign key into `events`, as for follows (V195) and RSVPs (V202): the BFF's daily cleanup
-- deletes a row whose event is gone, and every row after 30 days, read or not.
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004). `bff` gets its privileges from V193's default
-- privileges on `accounts`.

CREATE TABLE accounts.notification
(
    id         UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    account_id UUID        NOT NULL REFERENCES accounts.account (id) ON DELETE CASCADE,
    event_id   BIGINT      NOT NULL,
    kind       TEXT        NOT NULL CHECK (kind IN ('new_date')),
    about_kind TEXT        NOT NULL CHECK (about_kind IN ('artist', 'venue', 'promoter')),
    about_id   BIGINT      NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    read_at    TIMESTAMPTZ,
    UNIQUE (account_id, event_id, kind)
);

CREATE INDEX notification_account_idx ON accounts.notification (account_id, created_at DESC);
CREATE INDEX notification_event_idx ON accounts.notification (event_id);
