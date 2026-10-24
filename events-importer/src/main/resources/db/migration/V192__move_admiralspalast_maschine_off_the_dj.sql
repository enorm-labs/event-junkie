-- Admiralspalast bills Dieter "Maschine" Birr, the former Puhdys singer, as `Maschine`. The resident DJ Maschine at Der
-- Weiße Hase has the same name, so both acts shared one row, and the concert took the DJ's Techno tag (#3856).
-- SOURCE_ARTIST_SLUG_OVERRIDES now gives the Admiralspalast billing the slug `maschine-2`. This moves Admiralspalast's
-- stored links there, because a past night is never imported again. It drops the tags those nights derived from the
-- shared row, and lets MusicBrainz look at both rows afresh.

INSERT INTO artist (name, slug)
SELECT 'Maschine', 'maschine-2'
WHERE EXISTS (
    SELECT 1
    FROM event_artist ea
    JOIN artist a ON a.id = ea.artist_id
    JOIN event e ON e.id = ea.event_id
    WHERE a.slug = 'maschine' AND starts_with(e.source_id, 'admiralspalast:')
)
ON CONFLICT (slug) DO NOTHING;

UPDATE event_artist ea
SET artist_id = (SELECT id FROM artist WHERE slug = 'maschine-2')
FROM artist a, event e
WHERE a.id = ea.artist_id
  AND e.id = ea.event_id
  AND a.slug = 'maschine'
  AND starts_with(e.source_id, 'admiralspalast:');

DELETE FROM event_genre_tag egt
USING event_artist ea, artist a, event e
WHERE egt.event_id = ea.event_id
  AND ea.artist_id = a.id
  AND e.id = ea.event_id
  AND a.slug = 'maschine-2'
  AND starts_with(e.source_id, 'admiralspalast:')
  AND egt.origin = 'ARTIST';

UPDATE artist
SET musicbrainz_match = 'UNCHECKED', musicbrainz_checked_at = NULL
WHERE slug IN ('maschine', 'maschine-2') AND musicbrainz_match <> 'EXACT';
