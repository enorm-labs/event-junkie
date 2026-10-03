-- Two artist rows that name an event, not an act (#2416). Bi Nuu billed `Abraxas Party`, a party
-- named for the old Abraxas club, and Heimathafen billed `Auf Takt! Das Podcast-konzert`, a concert
-- format by klassix. The parser now drops both names. Both nights are past, and a past night is
-- never imported again, so each row keeps its link and OrphanArtistSweep never removes it.
--
-- A row is deleted only when every event it bills comes from its own source, so a same-slug row
-- billed elsewhere stays. The FK cascade on event_artist removes the links. The events stay.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TEMPORARY TABLE event_named_artist (
    slug          TEXT NOT NULL,
    source_prefix TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO event_named_artist (slug, source_prefix) VALUES
    ('abraxas-party',                'binuu:'),
    ('auf-takt-das-podcast-konzert', 'heimathafen:');

DELETE FROM artist a
USING event_named_artist n
WHERE a.slug = n.slug
  AND NOT EXISTS (
      SELECT 1
      FROM event_artist ea
      JOIN event e ON e.id = ea.event_id
      WHERE ea.artist_id = a.id
        AND e.source_id NOT LIKE n.source_prefix || '%'
  );
