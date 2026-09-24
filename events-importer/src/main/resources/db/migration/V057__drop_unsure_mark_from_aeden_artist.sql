-- ÆDEN writes `Octave?` for a booking it is unsure of, and the lineup parser now drops the mark
-- (#1843). `Octave` has the same slug as the minted `Octave?`, so the import reuses that row and
-- its name, and there is no re-seed on staging or production.
--
-- The slug already equals `slugify('Octave')`. Keyed on slug and the old name: a row that is
-- absent, or that a person has corrected since, is left alone.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

UPDATE artist
SET name = 'Octave'
WHERE slug = 'octave'
  AND name = 'Octave?';
