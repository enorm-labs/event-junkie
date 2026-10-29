-- V211 — a push on the day of an event an account marked as interested or going (#4253).
--
-- `account.reminder_push` is the account page's "Reminders on the day" switch. On by default, as `change_push` (V206):
-- the account marked the event and turned push on in a browser.
--
-- The BFF claims each Berlin day once on a third column of `accounts.push_cursor` (V203), so one replica sends. It starts
-- at the migration's day, so the first reminders go out on the morning after.
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004).

ALTER TABLE accounts.account ADD COLUMN reminder_push BOOLEAN NOT NULL DEFAULT true;

-- The table holds one row, and the default is STABLE, not VOLATILE: no rewrite.
-- squawk-ignore adding-field-with-default
ALTER TABLE accounts.push_cursor ADD COLUMN reminders_until DATE NOT NULL DEFAULT (now() AT TIME ZONE 'Europe/Berlin')::date;
