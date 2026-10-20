-- Three acts stored twice, once with an unnamed backing band (`Wenzel & Band` beside `Wenzel`), which the owner confirmed
-- (#3625). `stripArtistSuffix` now bills the bare name, so the next import links the survivor. The loser stays without
-- this migration: the orphan sweep keeps `Wenzel & Band` for its exact MusicBrainz match.
--
-- The shape of V152. Keyed on slug and a no-op where a row is absent. A link keeps its role, billing order, stage and set
-- times, and an event that already bills the survivor keeps its own link. Unqualified table names, deliberately: Flyway
-- sets `search_path` from `spring.flyway.schemas` before running this (ADR-004).

CREATE TEMPORARY TABLE artist_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_merge (loser, survivor, survivor_name) VALUES
    ('bial-hclap-band',     'bial-hclap',     'Bial Hclap'),
    ('christoph-titz-band', 'christoph-titz', 'Christoph Titz'),
    ('wenzel-band',         'wenzel',         'Wenzel');

-- 1. A missing survivor is created, only when its loser exists.
INSERT INTO artist (name, slug)
SELECT m.survivor_name, m.survivor
FROM artist_merge m
WHERE EXISTS (SELECT 1 FROM artist l WHERE l.slug = m.loser)
  AND NOT EXISTS (SELECT 1 FROM artist s WHERE s.slug = m.survivor);

-- 2. The loser's links, moved onto the survivor.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, s.id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM event_artist ea
JOIN artist l ON l.id = ea.artist_id
JOIN artist_merge m ON m.loser = l.slug
JOIN artist s ON s.slug = m.survivor
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 3. The losers, gone with their links.
DELETE FROM artist l
USING artist_merge m
WHERE l.slug = m.loser
  AND EXISTS (SELECT 1 FROM artist s WHERE s.slug = m.survivor);
