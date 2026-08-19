-- V002 — the first incremental migration, and the moment the single-migration window closed.
--
-- Everything before this lived in V001, consolidated on the rule in AGENTS.md: while nothing was
-- deployed, editing the initial migration was free and kept the schema readable in one file. That
-- stopped being true the moment a database existed with V001 already applied — editing it then is a
-- checksum mismatch, the importer's context fails to start, and Flux rolls the release back. The
-- symptom is "the deploy reverted", two layers from the cause.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this, so an unqualified migration follows the configuration and a qualified one
-- would pin itself to a schema the configuration no longer controls (ADR-004).

-- Last *successful* run, as distinct from last_import_at, which is written on failure too and
-- therefore means last attempt. Its own column because the difference is the whole point of
-- importer.source.last_success: a source that failed an hour ago still has a true last-success
-- time, and an alert on staleness needs it (#415).
--
-- Nullable with no default and no backfill. A backfill would have to invent a value, and the only
-- candidate is last_import_at — which is exactly the wrong one, because it is written on failure
-- too. Asserting a success that did not happen is worse than absence: an absent series is
-- alertable, a wrong one is not. Every source publishes nothing here until its next good run.
ALTER TABLE event_source
    ADD COLUMN last_success_at TIMESTAMPTZ;
