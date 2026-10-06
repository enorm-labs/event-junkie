-- When an event's page last read differently, for the sitemap's `<lastmod>` (#2768).
--
-- `updated_at` moves on every write, changed or not, and Google discounts a `lastmod` that moves without a change.
-- `content_hash` is a SHA-256 of what the event page shows. The importer moves `content_changed_at` only when that hash
-- differs from the stored one. `EventContentStamp` in the importer names the fields.
--
-- Backfilled from `updated_at`, the best date there is. `content_hash` starts NULL: the first import after this migration
-- stores the hash and keeps the backfilled date, so the deploy does not stamp every event as changed at once. A later
-- change to the hashed fields sets `content_hash` back to NULL in its own migration, for the same reason.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` before running this (ADR-004).

ALTER TABLE event
    ADD COLUMN content_hash       TEXT,
    ADD COLUMN content_changed_at TIMESTAMPTZ;

-- The trigger would move `updated_at` on every row, which this backfill only reads.
ALTER TABLE event DISABLE TRIGGER trg_event_updated_at;

UPDATE event
SET content_changed_at = updated_at;

ALTER TABLE event ENABLE TRIGGER trg_event_updated_at;
