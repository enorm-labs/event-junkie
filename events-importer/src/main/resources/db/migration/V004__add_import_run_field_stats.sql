-- V004 — per-field coverage tracking and the source flag it raises (#472).
--
-- Its own migration rather than an edit to V001: that window closed on 2026-08-19, see ADR-005's
-- amendment. Unqualified table names, because Flyway sets `search_path` from
-- `spring.flyway.schemas` before running this, and a qualified migration would pin itself to a
-- schema the configuration no longer controls (ADR-004).

-- Set when a run finds materially less of some field than this source normally publishes, cleared
-- when the next run looks normal again. Deliberately NOT the same thing as `status = FAILED`: a
-- source can be flagged while every run succeeds, which is the entire failure this catches — the
-- importer keeps working and the data quietly gets worse.
--
-- Both nullable with no default and no backfill: NULL is "not flagged", which is the correct state
-- for every existing row. There is no history to derive a flag from, and inventing one would raise
-- an alarm about a run nobody observed.
ALTER TABLE event_source
    ADD COLUMN flagged_at  TIMESTAMPTZ,
    ADD COLUMN flag_reason TEXT;

-- ============================================================
-- TABLE: import_run_field_stats — what each run actually extracted
--
-- #415 alerts when a source imports ZERO events, which catches a scraper that
-- broke completely. The quieter and more common failure is partial: a venue
-- moves the price into a different element, one selector stops matching, and
-- the importer keeps running, reports success, and writes the same forty events
-- it always does — every one of them now missing a price. Counts are unchanged,
-- so no count-based alert fires. Per-field coverage is the measurement that sees
-- it.
--
-- APPEND-ONLY, one row per (run, field), for three reasons: the history *is* the
-- baseline, the trend comes free, and there is no row to corrupt when a bad run
-- would otherwise overwrite a good one. A mutable "current fields" list has the
-- opposite property — the first broken run destroys the evidence that it broke.
--
-- `run_id` groups the ~14 rows one run writes. It is a UUID minted by the
-- importer rather than a sequence, because there is no run table to draw an id
-- from and inventing one for this would be a bigger change than the feature.
--
-- ON DELETE CASCADE here, unlike data_quality_snapshot's denormalised slug: that
-- table's history is about the corpus and outlives a source, whereas this one's
-- only meaning is "what this source's scraper extracted", which is nothing once
-- the source is gone.
-- ============================================================
CREATE TABLE import_run_field_stats
(
    id                BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    run_id            UUID        NOT NULL,
    source_id         BIGINT      NOT NULL REFERENCES event_source (id) ON DELETE CASCADE,
    field             TEXT        NOT NULL,
    -- Both numbers, never a ratio: a ratio cannot be re-aggregated, and the
    -- denominator is the minimum-sample guard's whole input. A run that scraped
    -- three events proves nothing about coverage and must not move a baseline.
    events_total      INT         NOT NULL,
    events_with_value INT         NOT NULL,
    observed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (run_id, field)
);

-- The baseline query: one field for one source, most recent runs first.
CREATE INDEX idx_import_run_field_stats_baseline
    ON import_run_field_stats (source_id, field, observed_at DESC);
