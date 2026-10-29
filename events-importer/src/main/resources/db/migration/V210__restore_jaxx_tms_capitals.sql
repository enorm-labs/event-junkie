-- Studio IIII bills `JAXX TMS`, and the de-shouter stored `Jaxx Tms` (#4224).
-- A row keeps the name whichever import minted it, so the code fix alone changes nothing that exists.
-- The slug stays, so no link moves. A name changed by hand since is left as it is.
--
-- The name is pinned (V101), as in V182: the rename moves `name_changed_at` (V062), and an
-- enrichment match would otherwise write MusicBrainz's letter case back.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

UPDATE artist
SET name = 'Jaxx TMS', name_pinned = true
WHERE slug = 'jaxx-tms'
  AND name = 'Jaxx Tms';
