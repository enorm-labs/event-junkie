-- V158 — the tokens the event-classification check used, per calendar month (#2996).
--
-- The check stops when a month's input and output tokens reach
-- `app.data-quality.classification.monthly-token-budget`. Spring AI's token meter cannot hold that
-- number: it lives in the process, so a restart sets it to zero, and translation's calls share it.
-- One row per month, added to after each model call.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TABLE event_classification_usage
(
    -- The first day of the month, in UTC.
    month_start   DATE        PRIMARY KEY CHECK (extract(DAY FROM month_start) = 1),
    input_tokens  BIGINT      NOT NULL DEFAULT 0 CHECK (input_tokens >= 0),
    output_tokens BIGINT      NOT NULL DEFAULT 0 CHECK (output_tokens >= 0),
    calls         INTEGER     NOT NULL DEFAULT 0 CHECK (calls >= 0),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
