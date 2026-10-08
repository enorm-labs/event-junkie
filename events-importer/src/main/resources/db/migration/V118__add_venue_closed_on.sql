-- A venue that closes for good keeps its row and its page (ADR-046, #2731). `closed_on` is the last day it was open.
-- From the day after, the public lists, filters and the map leave it out, and its page says it closed.
-- NULL means open. Set through the admin API, never by an import: a closure is a fact a person confirms (#2812).
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).
ALTER TABLE venue
    ADD COLUMN closed_on DATE;
