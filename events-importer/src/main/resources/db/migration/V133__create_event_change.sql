-- What changed on an event, for the event page: a moved start, a new date, a cancellation (#2725).
--
-- The importer writes a row when it updates an existing event and a tracked field differs from the stored value, and an
-- admin edit does the same. An insert writes nothing, so a new source or a re-keyed event logs no change. A side table
-- rather than a JSONB column on `event`, so the event row is not rewritten to append a change.
--
-- `field` is `EventChangeField` in events-core. The values are text: an ISO date, an `HH:mm:ss` time, an `EventStatus`
-- name, a venue id. A null on either side is not a change, so both are NOT NULL. Rows go with the event (ON DELETE
-- CASCADE), and each import deletes its source's rows of ended events and rows older than the 14 days the page shows.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` before running this
-- (ADR-004).

CREATE TABLE event_change
(
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id  BIGINT      NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    field     TEXT        NOT NULL CHECK (field IN ('EVENT_DATE', 'START_TIME', 'END_DATE', 'END_TIME', 'STATUS', 'VENUE')),
    old_value TEXT        NOT NULL,
    new_value TEXT        NOT NULL,
    seen_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The event page reads one event's recent rows, newest first.
CREATE INDEX idx_event_change_event_seen ON event_change (event_id, seen_at DESC);
