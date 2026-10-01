-- Junction Bar billed `LA RONY acoustic set`, and the format phrase stayed in the artist's name
-- (#2208). The parser now strips it, so the next import links `la-rony`. The 2026-10-01 night has
-- left the venue's page by then and is never imported again, so the row is renamed here.
--
-- Keyed on slug and the old name, so a row a person corrected since is left alone. When `la-rony`
-- already exists, the links move to it and the old row goes, as V074 does. Otherwise the old row
-- takes the new name and slug, and keeps its id.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, survivor.id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM artist loser
JOIN artist survivor ON survivor.slug = 'la-rony'
JOIN event_artist ea ON ea.artist_id = loser.id
WHERE loser.slug = 'la-rony-acoustic-set'
  AND loser.name = 'LA Rony acoustic set'
ON CONFLICT (event_id, artist_id) DO NOTHING;

DELETE FROM artist loser
USING artist survivor
WHERE loser.slug = 'la-rony-acoustic-set'
  AND loser.name = 'LA Rony acoustic set'
  AND survivor.slug = 'la-rony';

UPDATE artist
SET name = 'LA Rony',
    slug = 'la-rony'
WHERE slug = 'la-rony-acoustic-set'
  AND name = 'LA Rony acoustic set'
  AND NOT EXISTS (SELECT 1 FROM artist WHERE slug = 'la-rony');
