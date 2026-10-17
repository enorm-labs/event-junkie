-- V171 — a festival: one date range across many venues (#3272).
--
-- The home page points to the festival running today, or to the next one within 14 days. A row
-- replaces the hard-coded notice a festival week needed before, and its removal after the week.
-- `first_day` and `last_day` are Berlin dates. The notices are one or two sentences per locale.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TABLE festival
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    slug       TEXT        NOT NULL UNIQUE,
    name       TEXT        NOT NULL,
    url        TEXT        NOT NULL,
    first_day  DATE        NOT NULL,
    last_day   DATE        NOT NULL,
    notice_de  TEXT        NOT NULL,
    notice_en  TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT festival_days_ordered CHECK (last_day >= first_day)
);

-- The public read asks for the festival whose last day is today or later.
CREATE INDEX idx_festival_last_day ON festival (last_day);

CREATE TRIGGER trg_festival_updated_at
    BEFORE UPDATE
    ON festival
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
