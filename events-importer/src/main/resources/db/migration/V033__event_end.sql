-- An event's end, when the venue states one (ADR-029, #317). Both nullable, and both empty for
-- most rows for good: a source that publishes a start only leaves them NULL, and the read side
-- falls back to `event_date`. A date without a time is a run (an exhibition on for weeks); a time
-- without a date is not stored — the scraper resolves "22:00 – 06:00" to the next day before it
-- gets here, so `end_date` is always set when `end_time` is.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE event
    ADD COLUMN end_date DATE,
    ADD COLUMN end_time TIME,
    ADD CONSTRAINT event_end_after_start CHECK (end_date IS NULL OR end_date >= event_date),
    ADD CONSTRAINT event_end_time_has_date CHECK (end_time IS NULL OR end_date IS NOT NULL);
