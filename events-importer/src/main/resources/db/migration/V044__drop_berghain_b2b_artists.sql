-- Berghain writes a back-to-back slot's join inside the name span as often as in a marker span of
-- its own, and the scraper only read the second form, so `Agata B2B Cunt Remember` and
-- `Egregore B2B Jolly` were minted as one artist each (#1759). The scraper is fixed; this removes
-- the two rows, because there is no re-seed on staging or production.
--
-- All four performers already hold their own artist row, so the next import links the Terenor night
-- to those and nothing is left without a page. The FK cascade on event_artist removes the links.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist
WHERE slug IN (
    'agata-b2b-cunt-remember',
    'egregore-b2b-jolly'
);
