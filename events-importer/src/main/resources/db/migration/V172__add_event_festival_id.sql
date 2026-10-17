-- V172 — the festival an event belongs to (#3273). At most one per event.
--
-- Set and cleared only through `PUT /api/admin/events/{id}`, never by an import, as `featured_until`
-- (V130): no source knows of it. Deleting a festival clears the link and keeps the events.
--
-- The partial index serves the admin and public `festival` filters, which read only the few linked rows.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).
-- A new column holds no value yet, so the check finds nothing to scan, and the lock lasts as long as the
-- ALTER. `NOT VALID` buys nothing on a table this size.
-- squawk-ignore adding-foreign-key-constraint
ALTER TABLE event ADD COLUMN festival_id BIGINT REFERENCES festival (id) ON DELETE SET NULL;

CREATE INDEX idx_event_festival_id ON event (festival_id) WHERE festival_id IS NOT NULL;
