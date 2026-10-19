-- MICHA writes its name in capitals, and the de-shouter stored `Micha` before the curated entry (#3623).
-- A row keeps the name whichever import minted it, so the code fix alone changes nothing that exists.
-- The slug stays, so no link moves. A name changed by hand since is left as it is.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

UPDATE artist
SET name = 'MICHA'
WHERE slug = 'micha'
  AND name = 'Micha';
