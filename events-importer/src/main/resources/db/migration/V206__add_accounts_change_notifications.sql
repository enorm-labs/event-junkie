-- V206 — a push and a notification when an event an account marked as interested or going changes (#4045).
--
-- The changes come from `events.event_change` (V133), which the importer and the admin edit already write. The BFF
-- reads it past its own cursor, a second column on `accounts.push_cursor` (V203). It starts at the migration's time,
-- so the first run announces nothing that changed before.
--
-- `account.change_push` is the account page's "Changes to my plans" switch. On by default: the account marked the event
-- and turned push on in a browser. It covers the push only; the notification is written either way.
--
-- A change notification has `kind = 'change'` and `about_kind = 'rsvp'`, with the event's id as `about_id`, so a BFF
-- still on the old code reads the row without a null. `change` says what changed, the most severe of one window.
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004).

ALTER TABLE accounts.push_cursor ADD COLUMN changes_until TIMESTAMPTZ NOT NULL DEFAULT now();

ALTER TABLE accounts.account ADD COLUMN change_push BOOLEAN NOT NULL DEFAULT true;

ALTER TABLE accounts.notification ADD COLUMN change TEXT
    CHECK (change IN ('cancelled', 'postponed', 'relocated', 'date', 'time', 'venue'));

-- Widen both CHECKs by one value: each next line adds it back with every old value kept, in the same transaction.
-- squawk-ignore ban-drop-constraint
ALTER TABLE accounts.notification DROP CONSTRAINT notification_kind_check;
ALTER TABLE accounts.notification ADD CONSTRAINT notification_kind_check
    CHECK (kind IN ('new_date', 'change'));
-- squawk-ignore ban-drop-constraint
ALTER TABLE accounts.notification DROP CONSTRAINT notification_about_kind_check;
ALTER TABLE accounts.notification ADD CONSTRAINT notification_about_kind_check
    CHECK (about_kind IN ('artist', 'venue', 'promoter', 'rsvp'));
ALTER TABLE accounts.notification ADD CONSTRAINT notification_change_kind_check
    CHECK ((kind = 'change') = (change IS NOT NULL));
