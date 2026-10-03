-- An artist row that is a lead-in label, not an act (#2418). Renate's 2026-10-03 Klubnacht put
-- `Performances by:` on its own line above the performers, and the parser billed it. The parser now
-- drops a line that ends in a colon. The night is past, and a past night is never imported again,
-- so the row keeps its link and OrphanArtistSweep never removes it.
--
-- The row is deleted only when every event it bills comes from Renate, so a same-slug row billed
-- elsewhere stays. The FK cascade on event_artist removes the link. The event stays.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist a
WHERE a.slug = 'performances-by'
  AND NOT EXISTS (
      SELECT 1
      FROM event_artist ea
      JOIN event e ON e.id = ea.event_id
      WHERE ea.artist_id = a.id
        AND e.source_id NOT LIKE 'renate:%'
  );
