-- V208 — the removed event's id on a date move logged across a re-keyed source id (#4139).
--
-- On a source whose `sourceId` carries the date, a moved date is a removal plus an insert, and the importer logs the move
-- on the new event (#3067). An RSVP or a notification still points at the removed id. The BFF's change job reads this
-- column and moves those rows to `event_id` before it matches RSVPs. Null on every other row.
--
-- No foreign key: the removed row is gone by the time the change is written.
--
-- Unqualified table name: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

ALTER TABLE event_change ADD COLUMN previous_event_id BIGINT;

CREATE INDEX idx_event_change_previous_event ON event_change (previous_event_id) WHERE previous_event_id IS NOT NULL;
