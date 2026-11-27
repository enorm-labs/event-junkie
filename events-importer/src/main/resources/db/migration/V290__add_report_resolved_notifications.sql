-- V290 — a note under the bell when the operator resolves an account's problem report (#5308).
--
-- A report note has `kind = 'report'` and `about_kind = 'report'`. It has no `event_id`: a report can be about an act,
-- a promoter or a venue. `subject_kind` and `about_id` name the page, and `subject_title` keeps its name as it was at the
-- resolve, so a renamed or deleted page still reads right. `outcome` says what the operator did. `reply` is the
-- operator's optional answer to the reporter, at most 500 characters.
--
-- The UNIQUE constraint ignores rows with a null `event_id`, so a second resolve of the same page adds a second note.
-- The importer writes these rows (ADR-053 keeps the `accounts` migrations here). The BFF's daily cleanup deletes every
-- row after 30 days, read or not.
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004).

-- A BFF of the previous release joins the event: during the rollout it does not show or count a report note, and its
-- cleanup can delete one. Nothing writes a report note until the operator resolves with the new importer.
-- squawk-ignore ban-drop-not-null
ALTER TABLE accounts.notification ALTER COLUMN event_id DROP NOT NULL;

ALTER TABLE accounts.notification
    ADD COLUMN subject_kind  TEXT CHECK (subject_kind IN ('event', 'artist', 'promoter', 'venue')),
    ADD COLUMN subject_title TEXT,
    ADD COLUMN outcome       TEXT CHECK (outcome IN ('fixed', 'unchanged')),
    ADD COLUMN reply         TEXT CHECK (length(reply) BETWEEN 1 AND 500);

-- Widen both CHECKs by one value: each next line adds it back with every old value kept, in the same transaction.
-- squawk-ignore ban-drop-constraint
ALTER TABLE accounts.notification DROP CONSTRAINT notification_kind_check;
ALTER TABLE accounts.notification ADD CONSTRAINT notification_kind_check
    CHECK (kind IN ('new_date', 'change', 'report'));
-- squawk-ignore ban-drop-constraint
ALTER TABLE accounts.notification DROP CONSTRAINT notification_about_kind_check;
ALTER TABLE accounts.notification ADD CONSTRAINT notification_about_kind_check
    CHECK (about_kind IN ('artist', 'venue', 'promoter', 'rsvp', 'report'));

-- A report note has a subject, a title and an outcome and no event. Every other note has an event and none of them.
ALTER TABLE accounts.notification ADD CONSTRAINT notification_report_check
    CHECK (
        CASE
            WHEN kind = 'report' THEN event_id IS NULL AND about_kind = 'report' AND subject_kind IS NOT NULL
                AND subject_title IS NOT NULL AND outcome IS NOT NULL
            ELSE event_id IS NOT NULL AND about_kind <> 'report' AND subject_kind IS NULL AND subject_title IS NULL
                AND outcome IS NULL AND reply IS NULL
            END
        );
