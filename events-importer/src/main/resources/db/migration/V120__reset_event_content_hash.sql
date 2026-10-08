-- `EventContentStamp` hashes the venue's own second-language text from now on (#2849). That changes every stored hash.
--
-- A NULL `content_hash` is a baseline, not a change: the first import after this migration stores the new hash and keeps
-- `content_changed_at`. Without this reset, that import would move the sitemap's `<lastmod>` of every imported event.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` before running this (ADR-004).

-- The trigger would move `updated_at` on every row, and nothing on the page changed.
ALTER TABLE event DISABLE TRIGGER trg_event_updated_at;

UPDATE event
SET content_hash = NULL
WHERE content_hash IS NOT NULL;

ALTER TABLE event ENABLE TRIGGER trg_event_updated_at;
