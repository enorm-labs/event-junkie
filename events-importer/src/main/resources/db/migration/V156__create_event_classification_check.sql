-- V156 — the text each event had when the event-classification check last read it (#3241).
--
-- The check sends an event's title, subtitle and description to the model once. This row holds a
-- SHA-256 of that text, so an unchanged event is not sent again and a dismissed suggestion stays
-- dismissed. A changed text gets a new hash, and the event is a candidate again. A failed call
-- writes no row. ON DELETE CASCADE, because a deleted event has nothing to check.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TABLE event_classification_check
(
    event_id     BIGINT      PRIMARY KEY REFERENCES event (id) ON DELETE CASCADE,
    -- Hex SHA-256 of title, subtitle and description, joined by chr(31). Computed in SQL by the check.
    content_hash TEXT        NOT NULL,
    -- The model that answered, such as `claude-haiku-4-5`.
    model        TEXT        NOT NULL,
    checked_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
