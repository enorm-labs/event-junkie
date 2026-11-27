-- V288 — "Report a problem" on venue, act and promoter pages too (#5603).
--
-- `accounts.report` replaces V220's `event_report`: one row per account and subject, where the subject is an event,
-- an artist, a promoter or a venue. `kind` is lowercase, as `accounts.follow.kind` (V195). No foreign key into
-- `events`, as for follows and RSVPs: the BFF's daily cleanup deletes a report whose subject is gone.
--
-- `problems` holds the names of the BFF's `ReportProblem`. The BFF checks which of them each kind allows; the CHECK
-- lists them all. Every `event_report` row is copied as an event report.
--
-- `event_report` stays until the release after this one: the BFF pods of the previous release read it while the
-- rollout runs, and a dropped table would answer them 500.
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004). `bff` gets its privileges from V193's default
-- privileges on `accounts`.

CREATE TABLE accounts.report
(
    account_id UUID        NOT NULL REFERENCES accounts.account (id) ON DELETE CASCADE,
    kind       TEXT        NOT NULL CHECK (kind IN ('event', 'artist', 'promoter', 'venue')),
    subject_id BIGINT      NOT NULL,
    problems   TEXT[]      NOT NULL CHECK (
        cardinality(problems) BETWEEN 1 AND 24
            AND problems <@ ARRAY ['ARTISTS', 'PROMOTER', 'GENRE', 'EVENT_TYPE', 'FEATURES', 'DATE_TIME', 'TEXT', 'STATUS',
                'PRICE_TICKETS', 'NOT_AN_EVENT', 'ON_IMAGE', 'TITLE', 'DESCRIPTION', 'TYPE_GENRE', 'DUPLICATE', 'OTHER',
                'QUESTION', 'NAME', 'ADDRESS', 'LINKS', 'CLOSED', 'WRONG_ACT', 'IMAGE', 'NOT_A_PROMOTER']
        ),
    note       TEXT CHECK (length(note) BETWEEN 1 AND 500),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, kind, subject_id)
);

CREATE INDEX report_subject_idx ON accounts.report (kind, subject_id);
CREATE INDEX report_updated_idx ON accounts.report (updated_at);

INSERT INTO accounts.report (account_id, kind, subject_id, problems, note, created_at, updated_at)
SELECT account_id, 'event', event_id, problems, note, created_at, updated_at
FROM accounts.event_report;
