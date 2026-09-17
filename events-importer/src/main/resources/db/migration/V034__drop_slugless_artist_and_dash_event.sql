-- Arcanoa's `-- geschlossene Gesellschaft --` night was stored as a concert titled `-` with an
-- artist named `-` whose slug is the empty string (#1553). The scraper now skips the night and
-- `isNonArtistName` refuses a name that slugs to nothing; this removes the rows that exist, because
-- there is no re-seed on staging or production. Keyed on the empty slug and the bare-dash title,
-- neither of which a real row can carry, and a no-op where both are absent. The FK cascades on
-- event_artist remove the links.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM event
WHERE title = '-';

DELETE FROM artist
WHERE slug = '';
