-- HMOT, D'CO and LVFT write their names in capitals, and the de-shouter stored `Hmot`, `D'co` and `Lvft` (#3641).
-- A row keeps the name whichever import minted it, so the code fix alone changes nothing that exists.
-- The slug stays, so no link moves. A name changed by hand since is left as it is.
--
-- The name is pinned (V101). MusicBrainz spells `Hmot` and `D'co`, and the rename moves `name_changed_at` (V062).
-- The next lookup would then match the row again, and the enrichment would write MusicBrainz's letter case back.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

UPDATE artist
SET name = 'HMOT', name_pinned = true
WHERE slug = 'hmot'
  AND name = 'Hmot';

UPDATE artist
SET name = 'D''CO', name_pinned = true
WHERE slug = 'd-co'
  AND name = 'D''co';

UPDATE artist
SET name = 'LVFT', name_pinned = true
WHERE slug = 'lvft'
  AND name = 'Lvft';
