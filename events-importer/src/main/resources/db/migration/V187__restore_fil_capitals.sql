-- FIL (Philip Tägert) bills himself in capitals, and the row was minted as `Fil`.
-- A row keeps the name whichever import minted it, so the code fix alone changes nothing that exists.
-- The slug stays, so no link moves. A name changed by hand since is left as it is.
--
-- The name is pinned (V101). MusicBrainz spells `Fil`, and without the pin the enrichment would write its letter case back (#2317).
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

UPDATE artist
SET name = 'FIL', name_pinned = true
WHERE slug = 'fil'
  AND name = 'Fil';
