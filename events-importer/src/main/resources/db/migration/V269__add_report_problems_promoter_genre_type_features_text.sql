-- V269 — a simpler "Report a problem" form: five more boxes, PROMOTER, GENRE, EVENT_TYPE, FEATURES and TEXT.
--
-- The form no longer offers TITLE, DESCRIPTION, TYPE_GENRE, DUPLICATE, OTHER and QUESTION, but stored rows hold them,
-- and the BFF stores OTHER for a report with a note and no box. So the new CHECK lists all 17 names of the BFF's
-- `ReportProblem`, and no row changes. No `IF EXISTS`, as in V247: a missing name must fail.

-- The CHECK is widened: the statement after the drop adds it back with every old value kept, in the same transaction.
-- squawk-ignore ban-drop-constraint
ALTER TABLE accounts.event_report DROP CONSTRAINT event_report_problems_check;

ALTER TABLE accounts.event_report
    ADD CONSTRAINT event_report_problems_check CHECK (
        cardinality(problems) BETWEEN 1 AND 17
            AND problems <@ ARRAY ['ARTISTS', 'PROMOTER', 'GENRE', 'EVENT_TYPE', 'FEATURES', 'DATE_TIME', 'TEXT', 'STATUS',
                'PRICE_TICKETS', 'NOT_AN_EVENT', 'ON_IMAGE', 'TITLE', 'DESCRIPTION', 'TYPE_GENRE', 'DUPLICATE', 'OTHER',
                'QUESTION']
        );
