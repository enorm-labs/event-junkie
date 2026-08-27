-- The per-source record of what the venue's robots.txt said, written by RobotsRulesCache through
-- EventImportService on every run.
--
-- ADR-007 best-practice #1 made the robots.txt check a manual step before a new importer is written.
-- Three of eighty importer packages record having done it, which is the evidence gap #790 exists to
-- close. Three columns rather than a single boolean because the two questions a reviewer asks are
-- different: whether the entry URL is permitted, and whether anybody has looked recently.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

-- Nullable with no default and no backfill. Every source reports nothing here until its next run
-- reads the file. A default of `true` would assert a check that never happened, and the whole point
-- of the column is that an unchecked source is visible as unchecked.
ALTER TABLE event_source
    ADD COLUMN robots_checked_at TIMESTAMPTZ,
    ADD COLUMN robots_allowed    BOOLEAN,
    ADD COLUMN robots_txt_url    TEXT;
