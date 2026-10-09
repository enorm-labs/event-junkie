-- Monarch bills the German band `GÖRE`. Its slug folded to `gore`, so the import stored it on the US band Gore's row
-- (#2942). ARTIST_SLUG_OVERRIDES now gives Göre the slug `goere`. This moves Monarch's stored links there, because a past
-- night is never imported again, and lets MusicBrainz look at both rows afresh.

INSERT INTO artist (name, slug)
SELECT 'Göre', 'goere'
WHERE EXISTS (
    SELECT 1
    FROM event_artist ea
    JOIN artist a ON a.id = ea.artist_id
    JOIN event e ON e.id = ea.event_id
    WHERE a.slug = 'gore' AND starts_with(e.source_id, 'monarch:')
)
ON CONFLICT (slug) DO NOTHING;

UPDATE event_artist ea
SET artist_id = (SELECT id FROM artist WHERE slug = 'goere')
FROM artist a, event e
WHERE a.id = ea.artist_id
  AND e.id = ea.event_id
  AND a.slug = 'gore'
  AND starts_with(e.source_id, 'monarch:');

UPDATE artist
SET musicbrainz_match = 'UNCHECKED', musicbrainz_checked_at = NULL
WHERE slug IN ('gore', 'goere') AND musicbrainz_match <> 'EXACT';
