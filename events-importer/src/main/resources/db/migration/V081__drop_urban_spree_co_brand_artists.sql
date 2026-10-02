-- Urban Spree titles two co-branded nights `<partner> x Urban Spree …`, and the importer minted the
-- title as acts (#2350): `Human Tree x Urban Spree Klubnacht` as one artist, and `aufnahme + wiedergabe
-- X Urban Spree` as `aufnahme` and `wiedergabe X Urban Spree`. The parser now bills neither title.
-- There is no re-seed on staging or production, and the 2026-10-03 night is past by the time this
-- runs, so an import does not rewrite its line-up. This removes the three rows.
--
-- A row is deleted only when every event it bills is an Urban Spree event, so a same-slug row from
-- another venue stays. The FK cascade on event_artist removes the links. The events stay.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist a
WHERE a.slug IN (
    'human-tree-x-urban-spree-klubnacht',
    'wiedergabe-x-urban-spree',
    'aufnahme'
)
  AND NOT EXISTS (
      SELECT 1
      FROM event_artist ea
      JOIN event e ON e.id = ea.event_id
      WHERE ea.artist_id = a.id
        AND e.source_id NOT LIKE 'urban\_spree:%'
  );
