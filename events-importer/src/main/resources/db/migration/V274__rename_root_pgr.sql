-- Hole 44 credits `Root PGR GmbH`, and the casing flattened PGR, so the import stored "Root Pgr". PGR now joins the
-- acronyms the casing keeps (#5399). The slug stays `root-pgr`, so an import reuses the row and never rewrites its name.
--
-- Keyed on slug and the old name: a row that is absent, or that a person has corrected since, is left alone.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

UPDATE promoter
SET name = 'Root PGR'
WHERE slug = 'root-pgr'
  AND name = 'Root Pgr';
