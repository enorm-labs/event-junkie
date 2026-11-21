-- Maschinenhaus bills the Hamburg act moé in concert. Gretchen, migas and Renate bill the DJ `moe.`. Both names slug to
-- `moe`, so the concerts shared the DJ's row. ARTIST_SLUG_OVERRIDES now gives `moé` the slug `moe-2`. This creates that
-- row and moves Maschinenhaus's stored links there, because a past night is never imported again.
--
-- Keyed on slug, name and source, so a row renamed by hand and every other source's billing are left alone. Unqualified
-- table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

INSERT INTO artist (name, slug)
SELECT 'moé', 'moe-2'
WHERE EXISTS (
    SELECT 1
    FROM event_artist ea
    JOIN artist a ON a.id = ea.artist_id
    JOIN event e ON e.id = ea.event_id
    WHERE a.slug = 'moe' AND a.name = 'moe.' AND starts_with(e.source_id, 'maschinenhaus:')
)
ON CONFLICT (slug) DO NOTHING;

UPDATE event_artist ea
SET artist_id = (SELECT id FROM artist WHERE slug = 'moe-2')
FROM artist a, event e
WHERE a.id = ea.artist_id
  AND e.id = ea.event_id
  AND a.slug = 'moe'
  AND a.name = 'moe.'
  AND starts_with(e.source_id, 'maschinenhaus:')
  AND EXISTS (SELECT 1 FROM artist WHERE slug = 'moe-2')
  AND NOT EXISTS (
      SELECT 1 FROM event_artist taken JOIN artist two ON two.id = taken.artist_id
      WHERE taken.event_id = ea.event_id AND two.slug = 'moe-2'
  );
