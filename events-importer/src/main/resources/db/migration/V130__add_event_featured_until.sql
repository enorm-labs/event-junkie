-- A curated pick for the events list (#1262): until `featured_until` passes, the event may lead the first page of
-- `/events` at double width. Of the featured events that match the visitor's filters, the earliest-starting one leads.
-- NULL means not featured. Set and cleared through `PUT /api/admin/events/{id}`, never by an import: a pick is a
-- person's choice, and the window keeps a forgotten pick from going stale.
--
-- The partial index holds only the few picked rows, so the lead query does not read every upcoming event.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).
ALTER TABLE event
    ADD COLUMN featured_until TIMESTAMPTZ;

CREATE INDEX idx_event_featured_until ON event (featured_until) WHERE featured_until IS NOT NULL;
