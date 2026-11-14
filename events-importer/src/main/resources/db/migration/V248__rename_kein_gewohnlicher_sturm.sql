-- Monarch writes `KEIN GEWöHNLICHER STURM`, an umlaut left small in a shouted title, so the billing was never de-shouted.
-- The scraper now capitalises the umlaut (#4996). `Kein Gewöhnlicher Sturm` has the same slug as the minted row, so the
-- import reuses that row and its name, and there is no re-seed on staging or production.
--
-- Keyed on slug and the old name: a row that is absent, or that a person has corrected since, is left alone.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

UPDATE artist
SET name = 'Kein Gewöhnlicher Sturm'
WHERE slug = 'kein-gewohnlicher-sturm'
  AND name = 'KEIN GEWöHNLICHER STURM';
