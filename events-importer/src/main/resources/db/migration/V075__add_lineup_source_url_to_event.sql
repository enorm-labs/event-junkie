-- The page a line-up was taken from when it is not the event's own source (ADR-036, #2187).
--
-- Sisyphos weekends take their running order from the fan-run sisy.fan, whose developer allows it on the condition
-- that sisy.fan is credited. A night the ticket shop also sells keeps the shop's page as `source_url`, so the credit
-- needs a column of its own. Null for every other event.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).
ALTER TABLE event
    ADD COLUMN lineup_source_url TEXT;
