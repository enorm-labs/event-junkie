-- Monster Ronson's title `SING WITH FAUXPAS & MC UNCUT GEM!` minted the act with the title's `!` (#3642).
-- A row keeps the name whichever import minted it, and that night is past, so the code fix alone changes nothing that exists.
-- The slug stays, so no link moves. A name changed by hand since is left as it is.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

UPDATE artist
SET name = 'MC Uncut Gem'
WHERE slug = 'mc-uncut-gem'
  AND name = 'MC Uncut Gem!';
