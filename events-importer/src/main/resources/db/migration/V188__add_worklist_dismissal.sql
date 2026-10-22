-- V188 — a data-quality worklist row the operator judged correct as it is (#3711, ADR-052).
--
-- The row holds the md5 of the values the check flagged when the operator judged it. The worklist leaves
-- an event out only while that fingerprint still matches, so a changed value brings the row back.
-- One dismissal per issue and event: dismissing again replaces the fingerprint.
--
-- ON DELETE CASCADE, because a judgment about a deleted event judges nothing. The daily data-quality job
-- deletes the dismissals of past events.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TABLE worklist_dismissal
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The `QualityIssue` enum name, such as TITLE_DERIVED_SINGLETONS.
    issue        TEXT        NOT NULL,
    event_id     BIGINT      NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    fingerprint  TEXT        NOT NULL,
    -- The admin API knows no user, so every dismissal names the one operator.
    dismissed_by TEXT        NOT NULL DEFAULT 'operator',
    dismissed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (issue, event_id)
);

CREATE INDEX idx_worklist_dismissal_event ON worklist_dismissal (event_id);
