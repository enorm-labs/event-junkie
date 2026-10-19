-- The new date of a RELOCATED event, as the venue's own note states it: Lido's "verlegt auf den 13.04.27 ins Hole 44".
-- A date, not a pointer to the row on that date: the note is the only reliable thing (ADR-030). Null on every other
-- status, and on a note that names no new date (#3613).
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` before running this (ADR-004).
ALTER TABLE event
    ADD COLUMN relocated_on DATE;

-- `EventContentStamp` hashes the new column, which changes every stored hash. A NULL hash is a baseline, not a change,
-- so the next import keeps `content_changed_at` (V120).
ALTER TABLE event DISABLE TRIGGER trg_event_updated_at;

UPDATE event
SET content_hash = NULL
WHERE content_hash IS NOT NULL;

ALTER TABLE event ENABLE TRIGGER trg_event_updated_at;
