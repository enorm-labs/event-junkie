-- V003 — the data-quality snapshot table (#319).
--
-- Its own migration rather than an edit to V001: that window closed on 2026-08-19, see ADR-005's
-- amendment. Unqualified table names, because Flyway sets `search_path` from
-- `spring.flyway.schemas` before running this, and a qualified migration would pin itself to a
-- schema the configuration no longer controls (ADR-004).

-- ============================================================
-- TABLE: data_quality_snapshot — one row per (source, metric, day)
--
-- Pillar 1 of DATA_QUALITY_STRATEGY.md is Measure, and the reason it comes
-- first is that without it every later pillar is judged on whether it *feels*
-- like it helped. A live report cannot do that: it only ever describes today,
-- so there is nothing for a fix to be measured against.
--
-- Append-only and keyed by day rather than updated in place. A row that is
-- overwritten cannot show a regression, and the whole point of the table is
-- that somebody can look back and see when a number started moving.
--
-- `source_slug` is denormalised rather than an FK, deliberately: history has to
-- survive the source being deleted, and `event.event_source_id` is already
-- ON DELETE SET NULL — so a foreign key here would silently take the history
-- with it exactly when somebody is asking what happened. 'manual' is the
-- synthetic bucket for events with no source, so nothing is silently excluded.
-- ============================================================
CREATE TABLE data_quality_snapshot
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The day the snapshot describes, not the instant it was written: the
    -- scheduled writer's clock drifting past midnight must not create a second
    -- row for the same day, which is what the unique constraint below enforces.
    snapshot_date DATE        NOT NULL,
    source_slug   TEXT        NOT NULL,
    metric        TEXT        NOT NULL,
    -- Both numbers, not a ratio. A percentage cannot be re-aggregated across
    -- sources or days without the denominator, and the denominator is itself a
    -- signal — 40% of 5 events and 40% of 500 are different findings.
    metric_count  BIGINT      NOT NULL,
    total_events  BIGINT      NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (snapshot_date, source_slug, metric)
);

-- The query every dashboard runs: one metric for one source over time.
CREATE INDEX idx_data_quality_snapshot_series ON data_quality_snapshot (source_slug, metric, snapshot_date);
