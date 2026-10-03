-- Modus Berlin's weekly `Modus Comedy` series was typed a concert, and the importer minted its name
-- as the headlining act (#2398). The parser now types it COMEDY and bills the comedians from the
-- lineup the page publishes. The next import relinks every upcoming night, but a past night is
-- never imported again, so the series row would stay listed on /artists. This removes it.
--
-- The row is deleted only when every event it bills is a Modus event, so a same-slug row billed
-- elsewhere stays. The FK cascade on event_artist removes the links. The events stay.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist a
WHERE a.slug = 'modus-comedy'
  AND NOT EXISTS (
      SELECT 1
      FROM event_artist ea
      JOIN event e ON e.id = ea.event_id
      WHERE ea.artist_id = a.id
        AND e.source_id NOT LIKE 'modus:%'
  );
