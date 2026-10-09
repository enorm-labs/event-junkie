-- V129 — the suggestion store for Pillar 4 (#474 part A).
--
-- A data-quality check proposes a fix and a person decides (ADR-041 rule 3). This table holds the
-- proposal and its evidence until somebody accepts or dismisses it. Nothing here writes to `event`:
-- accepting a suggestion goes through `PUT /api/admin/events/{id}`, which pins the field (ADR-042).
--
-- One row per proposal, not per event: two checks can disagree about the same field, and a steward
-- has to see both. ON DELETE CASCADE, because a proposal for a deleted event proposes nothing.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TABLE event_suggestion
(
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id       BIGINT           NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    -- The event field the proposal is for, as `PUT /api/admin/events/{id}` names it.
    field          TEXT             NOT NULL,
    -- NULL when the event has no value for the field: a missing start time is the common case.
    stored_value   TEXT,
    proposed_value TEXT             NOT NULL,
    -- What the check saw: the sentence of the description, or the passage of the source page.
    evidence       TEXT             NOT NULL,
    confidence     DOUBLE PRECISION NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    check_name     TEXT             NOT NULL,
    -- NULL for a deterministic check, which uses no model.
    model          TEXT,
    status         TEXT             NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'DISMISSED', 'ACCEPTED')),
    created_at     TIMESTAMPTZ      NOT NULL DEFAULT now()
);

-- The review list: open suggestions, newest first. The event filter uses the second index.
CREATE INDEX idx_event_suggestion_status_created ON event_suggestion (status, created_at DESC, id DESC);
CREATE INDEX idx_event_suggestion_event ON event_suggestion (event_id);
