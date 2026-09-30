-- The promoter rows Schokoladen's whole `span.promoter` minted (#2074). The scraper now cuts the
-- span at its "presents" word or tagline, splits co-promoters at "+" as well, keeps "thirsty &
-- miserable" whole and expands "lls". A row keeps the name whichever import minted it, so the rows
-- that map onto one promoter are merged here, in V029's shape; a no-op where a row is absent.
--
-- The three rows that stand for two promoters each are deleted instead. Their events are upcoming,
-- and the next import links them to both.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TEMPORARY TABLE promoter_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO promoter_merge (loser, survivor, survivor_name) VALUES
    ('m-soundtrack-bridging-the-psychedelic-traditions-of-latin-america-and-todays-global-dancefloor', 'm-soundtrack', 'm:soundtrack'),
    ('m-soundtrack-prsnts-a-friends-celebrating-friends-festival', 'm-soundtrack', 'm:soundtrack'),
    ('m-soundtrack-prsnts-falling-into-autumn-dreamy-indie',       'm-soundtrack', 'm:soundtrack'),
    ('little-league-shows-present', 'little-league-shows', 'little league shows'),
    ('lls',                         'little-league-shows', 'little league shows'),
    ('thirsty',                     'thirsty-miserable',   'thirsty & miserable');

UPDATE promoter p
SET slug = m.survivor, name = m.survivor_name
FROM (
    SELECT survivor, survivor_name, MIN(loser) AS loser
    FROM promoter_merge
    WHERE loser <> survivor
      AND EXISTS (SELECT 1 FROM promoter l WHERE l.slug = promoter_merge.loser)
      AND NOT EXISTS (SELECT 1 FROM promoter s WHERE s.slug = promoter_merge.survivor)
    GROUP BY survivor, survivor_name
) m
WHERE p.slug = m.loser;

UPDATE promoter p
SET name = m.survivor_name
FROM (SELECT DISTINCT survivor, survivor_name FROM promoter_merge) m
WHERE p.slug = m.survivor
  AND p.name <> m.survivor_name;

-- Links are inserted, not moved, as in V031: three losers share one survivor, and moving two links
-- of one event onto it trips UNIQUE (event_id, promoter_id). The cascade takes the old links.
INSERT INTO event_promoter (event_id, promoter_id)
SELECT DISTINCT ep.event_id, s.id
FROM promoter_merge m
JOIN promoter l ON l.slug = m.loser
JOIN promoter s ON s.slug = m.survivor
JOIN event_promoter ep ON ep.promoter_id = l.id
WHERE m.loser <> m.survivor
ON CONFLICT (event_id, promoter_id) DO NOTHING;

DELETE FROM promoter l
USING promoter_merge m
WHERE l.slug = m.loser
  AND m.loser <> m.survivor
  AND EXISTS (SELECT 1 FROM promoter s WHERE s.slug = m.survivor);

DELETE FROM promoter
WHERE slug IN (
    'miserable-crunch-tapes',
    'punkfilmfest-berlin-booking-the-living-proof',
    'trust-thirsty'
);
