-- Huxleys bills the Chicago band Wilco. Frannz Club and Der Weiße Hase bill a DJ of the same name, so the band's concert
-- shared the DJ's row. SOURCE_ARTIST_SLUG_OVERRIDES now sends Huxleys' billing to `wilco-2`. This creates that row and
-- moves Huxleys' stored links there.
--
-- Keyed on slug, name and source, so a row renamed by hand and every other source's billing are left alone. Unqualified
-- table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

INSERT INTO artist (name, slug)
SELECT 'Wilco', 'wilco-2'
WHERE EXISTS (
    SELECT 1
    FROM event_artist ea
    JOIN artist a ON a.id = ea.artist_id
    JOIN event e ON e.id = ea.event_id
    WHERE a.slug = 'wilco' AND a.name = 'Wilco' AND starts_with(e.source_id, 'huxleys:')
)
ON CONFLICT (slug) DO NOTHING;

UPDATE event_artist ea
SET artist_id = (SELECT id FROM artist WHERE slug = 'wilco-2')
FROM artist a, event e
WHERE a.id = ea.artist_id
  AND e.id = ea.event_id
  AND a.slug = 'wilco'
  AND a.name = 'Wilco'
  AND starts_with(e.source_id, 'huxleys:')
  AND EXISTS (SELECT 1 FROM artist WHERE slug = 'wilco-2')
  AND NOT EXISTS (
      SELECT 1 FROM event_artist taken JOIN artist two ON two.id = taken.artist_id
      WHERE taken.event_id = ea.event_id AND two.slug = 'wilco-2'
  );
