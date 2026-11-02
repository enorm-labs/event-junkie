-- V220 — "Report a problem" on an event page, for accounts the operator allows (#4399).
--
-- `account.can_report` is the allowlist. Only the importer's admin API sets it; the BFF reads it.
--
-- One report per account and event: a second one replaces the first. `problems` holds the ticked boxes, the names of
-- the BFF's `ReportProblem`. No foreign key into `events`, as for follows (V195) and RSVPs (V202): the BFF's daily
-- cleanup deletes a report whose event is gone, and every report after 60 days. Resolving one deletes it.
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004). `bff` gets its privileges from V193's default
-- privileges on `accounts`.

ALTER TABLE accounts.account ADD COLUMN can_report BOOLEAN NOT NULL DEFAULT false;

CREATE TABLE accounts.event_report
(
    account_id UUID        NOT NULL REFERENCES accounts.account (id) ON DELETE CASCADE,
    event_id   BIGINT      NOT NULL,
    problems   TEXT[]      NOT NULL CHECK (
        cardinality(problems) BETWEEN 1 AND 9
            AND problems <@ ARRAY ['ARTISTS', 'TITLE', 'DATE_TIME', 'STATUS', 'PRICE_TICKETS', 'TYPE_GENRE', 'NOT_AN_EVENT', 'DUPLICATE', 'OTHER']
        ),
    note       TEXT CHECK (length(note) BETWEEN 1 AND 500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, event_id)
);

CREATE INDEX event_report_event_idx ON accounts.event_report (event_id);
CREATE INDEX event_report_updated_idx ON accounts.event_report (updated_at);
