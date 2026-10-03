-- A per-source override of the time of day a scheduled import may start (#791, ADR-007 best practice 7).
--
-- Both NULL means the global window, `app.scheduling.import-window`. Both set replaces it for this source, read in the
-- global zone. Equal start and end mean the whole day. A start after the end wraps past midnight. No backfill: every
-- source takes the global window until an operator says otherwise.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE event_source
    ADD COLUMN import_window_start TIME,
    ADD COLUMN import_window_end   TIME;

-- One half of a window has no meaning, and the scheduler would have to guess which default to pair it with.
ALTER TABLE event_source
    ADD CONSTRAINT event_source_import_window_complete
        CHECK ((import_window_start IS NULL) = (import_window_end IS NULL));
