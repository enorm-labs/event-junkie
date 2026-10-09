-- Enrichment sources (ADR-043, #2593).
--
-- `event_source.role` says what a source does with its events. `MAIN` creates, updates and removes the events of its
-- venue, as every source does today, so every existing row takes it. `ENRICHMENT` never inserts and never deletes: it
-- fills the empty fields of the events that the venue's main source lists. No source is set to `ENRICHMENT` here.
-- Each one needs its licence reviewed and its credit in the privacy notice first (ADR-043 rule 6).
--
-- `event_enrichment` records which fields of an event each enrichment source filled, and the page it read them
-- from. The event page links that page (ADR-036). The main source's import reads the row: it keeps a filled value
-- where it has none of its own, and its own value wins and takes the field off the row. A row with no fields left
-- is deleted. ON DELETE CASCADE on both keys, because a record about a deleted event or source is about nothing.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE event_source
    ADD COLUMN role TEXT NOT NULL DEFAULT 'MAIN' CHECK (role IN ('MAIN', 'ENRICHMENT'));

CREATE TABLE event_enrichment
(
    event_id        BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    event_source_id BIGINT NOT NULL REFERENCES event_source (id) ON DELETE CASCADE,
    source_url      TEXT   NOT NULL,
    fields          TEXT[] NOT NULL CHECK (cardinality(fields) > 0),
    PRIMARY KEY (event_id, event_source_id)
);
