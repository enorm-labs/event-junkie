-- Huxleys bills the duo Haute & Freddy as its title, and the title parser split it at the `&`, so
-- the duo was stored as two artists, `Haute` and `Freddy` (#2063). The parser now asks
-- `isKnownSingleAct` first; this removes the two rows, because there is no re-seed on staging or
-- production. It follows V043.
--
-- A row is deleted only when every event it bills is the duo's own. An unrelated act of the same
-- name keeps its row. `freddy` also carries an EXACT Discogs link to another artist, which is why
-- the row goes and is not renamed. The FK cascade on event_artist removes the links, and the next
-- import stores the duo under its own name.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist a
WHERE a.slug IN ('haute', 'freddy')
  AND NOT EXISTS (
      SELECT 1
      FROM event_artist ea
      JOIN event e ON e.id = ea.event_id
      WHERE ea.artist_id = a.id
        AND lower(e.title) <> 'haute & freddy'
  );
