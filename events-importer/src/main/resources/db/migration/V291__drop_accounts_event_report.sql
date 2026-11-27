-- V291 — drop V220's `accounts.event_report` (#5607).
--
-- V288 (#5603) copied every row into `accounts.report`, and nothing has read `event_report` since. The table stayed for one
-- release, so the BFF pods of the release before V288 could read it during that rollout. This migration lands only after
-- a release with V288 runs on staging and production.
--
-- Qualified names: Flyway's `search_path` is `events` (ADR-004).

-- No release that reads the table is still running.
-- squawk-ignore ban-drop-table
DROP TABLE accounts.event_report;
