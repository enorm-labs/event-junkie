-- V275 — the month the BFF last checked the watched MusicBrainz ids for merges (#5225).
--
-- MusicBrainz merges duplicate artists, and the old id then redirects to the survivor. A watch (V259) on the old id never
-- meets an artist row, which carries the survivor's id. Once a month the BFF reads each watched id again and moves the
-- watch to the survivor. It claims the month on a fourth column of `accounts.push_cursor` (V203), so one replica reads
-- MusicBrainz. The column holds the first day of the claimed Berlin month. It starts at the migration's month, so the
-- first check runs at the start of the next one.
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004).

-- The table holds one row, and the default is STABLE, not VOLATILE: no rewrite.
-- squawk-ignore adding-field-with-default
ALTER TABLE accounts.push_cursor ADD COLUMN watches_checked_until DATE NOT NULL
    DEFAULT date_trunc('month', now() AT TIME ZONE 'Europe/Berlin')::date;
