-- V195 — what an account follows (#3889): an artist, a venue, a promoter or a festival, or a saved search (#3890).
--
-- One table for both halves, so a digest (#3891) reads one list. `notify` is the bell per follow; nothing
-- sends yet, so every follow starts `off`.
--
-- No foreign key into `events`: `bff` may only read there, and an importer delete must not fail on a
-- follow. A follow whose entity is gone drops out of every read by the join, and the BFF's daily cleanup
-- deletes it. The id, not the slug, because a rename changes the slug (V159).
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004). `bff` gets its privileges from V193's
-- default privileges on `accounts`.

CREATE TABLE accounts.follow
(
    id         UUID PRIMARY KEY     DEFAULT gen_random_uuid(),
    account_id UUID        NOT NULL REFERENCES accounts.account (id) ON DELETE CASCADE,
    kind       TEXT        NOT NULL CHECK (kind IN ('artist', 'venue', 'promoter', 'festival', 'search')),
    entity_id  BIGINT,
    query      TEXT,
    notify     TEXT        NOT NULL DEFAULT 'off' CHECK (notify IN ('off', 'digest', 'instant')),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT follow_target CHECK ((kind = 'search' AND query IS NOT NULL AND entity_id IS NULL)
        OR (kind <> 'search' AND entity_id IS NOT NULL AND query IS NULL))
);

CREATE UNIQUE INDEX follow_entity_key ON accounts.follow (account_id, kind, entity_id) WHERE kind <> 'search';
CREATE INDEX follow_kind_entity_idx ON accounts.follow (kind, entity_id) WHERE kind <> 'search';
