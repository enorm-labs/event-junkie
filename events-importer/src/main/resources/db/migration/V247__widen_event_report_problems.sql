-- V247 — three more boxes on "Report a problem" (#4978): DESCRIPTION, QUESTION and ON_IMAGE.
--
-- V220's CHECK on `problems` has no name, so Postgres named it `event_report_problems_check`. The new one lists the
-- 12 names of the BFF's `ReportProblem`, and every stored row holds a subset of the old 9. No `IF EXISTS`: a missing
-- name must fail, or the old CHECK would stay and refuse the new boxes.

-- The next statement adds it back with every old name kept, in the same transaction.
-- squawk-ignore ban-drop-constraint
ALTER TABLE accounts.event_report DROP CONSTRAINT event_report_problems_check;

ALTER TABLE accounts.event_report
    ADD CONSTRAINT event_report_problems_check CHECK (
        cardinality(problems) BETWEEN 1 AND 12
            AND problems <@ ARRAY ['ARTISTS', 'TITLE', 'DESCRIPTION', 'DATE_TIME', 'STATUS', 'PRICE_TICKETS', 'TYPE_GENRE',
                'NOT_AN_EVENT', 'DUPLICATE', 'OTHER', 'QUESTION', 'ON_IMAGE']
        );
