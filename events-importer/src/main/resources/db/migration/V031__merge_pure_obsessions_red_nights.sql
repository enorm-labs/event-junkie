-- Urban Spree credits the party "Pure Obsessions & Red Nights" with the same "&" it joins two
-- promoters with, so the split minted `pure-obsessions` and `red-nights` beside the reviewed row
-- `pure-obsessions-red-nights` (#1356). The scraper now keeps that name whole; this folds the two
-- rows that exist back into it. The same four-step shape as V029, keyed on slug and a no-op where
-- a row is absent.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TEMPORARY TABLE promoter_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO promoter_merge (loser, survivor, survivor_name) VALUES
    ('pure-obsessions', 'pure-obsessions-red-nights', 'Pure Obsessions & Red Nights'),
    ('red-nights',      'pure-obsessions-red-nights', 'Pure Obsessions & Red Nights');

-- 1. A missing survivor is the smallest of its losers that exists, renamed.
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

-- 2. The display name, on every survivor that exists.
UPDATE promoter p
SET name = m.survivor_name
FROM (SELECT DISTINCT survivor, survivor_name FROM promoter_merge) m
WHERE p.slug = m.survivor
  AND p.name <> m.survivor_name;

-- 3. The losers' events, linked to the survivor. An insert rather than an update of the loser's
--    link: two losers on one event would both move onto the survivor in one statement, and the
--    UNIQUE (event_id, promoter_id) refuses the second. The cascade in step 4 takes the old links.
INSERT INTO event_promoter (event_id, promoter_id)
SELECT DISTINCT ep.event_id, s.id
FROM event_promoter ep
JOIN promoter l ON l.id = ep.promoter_id
JOIN promoter_merge m ON m.loser = l.slug
JOIN promoter s ON s.slug = m.survivor
WHERE m.loser <> m.survivor
ON CONFLICT (event_id, promoter_id) DO NOTHING;

-- 4. The losers, gone. The cascade on event_promoter.promoter_id takes their links.
DELETE FROM promoter l
USING promoter_merge m
WHERE l.slug = m.loser
  AND m.loser <> m.survivor
  AND EXISTS (SELECT 1 FROM promoter s WHERE s.slug = m.survivor);
