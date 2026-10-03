-- The guard every per-source artist drop restated by hand (V071, V081, V085, V087, V091, #2467). A later data fix calls
-- `SELECT drop_artist_billed_only_by('<slug>', '<source>:');` once per row.
--
-- The row is deleted only when every event it bills comes from that source, so a same-slug row billed elsewhere stays.
-- The FK cascade on event_artist removes the links, and the events stay. Returns the number of rows deleted.
--
-- starts_with, not LIKE: `_` in a prefix such as `urban_spree:` is a LIKE wildcard, which V081 had to escape by hand.
-- BEGIN ATOMIC binds the tables when the function is created, so a call does not depend on the caller's search_path.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE FUNCTION drop_artist_billed_only_by(artist_slug TEXT, source_prefix TEXT) RETURNS INTEGER
    LANGUAGE sql
    STRICT
BEGIN ATOMIC
    WITH dropped AS (
        DELETE FROM artist a
        WHERE a.slug = artist_slug
          AND NOT EXISTS (
              SELECT 1
              FROM event_artist ea
              JOIN event e ON e.id = ea.event_id
              WHERE ea.artist_id = a.id
                AND NOT starts_with(e.source_id, source_prefix)
          )
        RETURNING 1
    )
    SELECT count(*)::INTEGER FROM dropped;
END;
