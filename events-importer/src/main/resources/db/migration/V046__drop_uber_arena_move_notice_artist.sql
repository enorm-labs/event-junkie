-- Uber Arena writes a move as `VENUE ÄNDERUNG: <act>` in the title, and none of the relocation
-- words matched it, so the notice reached the lineup as an act of its own (#1771). The scraper is
-- fixed; this removes the row, because there is no re-seed on staging or production.
--
-- `jazeek` already exists from the arriving row at the Uber Eats Music Hall, so the next import
-- links the moved event to that. The FK cascade on event_artist removes the link.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist
WHERE slug = 'venue-anderung-jazeek';
