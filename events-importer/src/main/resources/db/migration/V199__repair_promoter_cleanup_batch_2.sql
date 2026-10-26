-- The second promoter /artist-cleanup batch. The normalizer now restores six promoters from what the strip left of their
-- credit, and refuses two parties crediting themselves, an improv troupe, two bingo hosts and two people on a film night.
-- Five scrapers split a credit that names several promoters, and Berghain no longer splits "Now And Ever Music". A past
-- event is never imported again, so this repairs the rows already stored.
--
-- "MB –", "Berlin", "Bossa", "Now" and "Ever" merge into an existing promoter. "Capital" and the Como Esta Wally company
-- are renamed, and "so36" keeps its slug and gets its capitals. Four rows that name two promoters are split.
--
-- The merge is V198's shape, keyed on slug and a no-op where a row is absent. The unlinked rows are left to
-- OrphanPromoterSweep. Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TEMPORARY TABLE promoter_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO promoter_merge (loser, survivor, survivor_name) VALUES
    ('mb',                                           'mb-konzerte',         'MB Konzerte'),
    ('berlin',                                       'new-berlin-konzerte', 'New Berlin Konzerte'),
    ('bossa',                                        'bossa-fm',            'Bossa FM'),
    ('capital',                                      'capital-music',       'Capital Music'),
    ('como-esta-wally-producciones-y-management-sl', 'como-esta-wally',     'Como Esta Wally'),
    ('so36',                                         'so36',                'SO36'),
    ('now',                                          'now-and-ever',        'Now And Ever'),
    ('ever',                                         'now-and-ever',        'Now And Ever');

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

-- 3. The losers' events, linked to the survivor. An insert, because an event can already credit
--    both rows, and two losers on one event would trip UNIQUE (event_id, promoter_id) on an update.
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

-- 5. Names that are no promoter, unlinked from every event.
DELETE FROM event_promoter ep
USING promoter p
WHERE p.id = ep.promoter_id
  AND p.slug IN (
      'atr-party-berlin',
      'girlstown',
      'girls-town-party',
      'thd-impro-twinkies-ho-hos-ding-dongs',
      'inge-borg-gisela-sommer',
      'maria-botelho-hugo-sousa'
  );

-- 6. Rows that name two promoters: each part linked to the row's events, created where it is missing, then the row gone.
CREATE TEMPORARY TABLE promoter_split (
    loser     TEXT NOT NULL,
    part      TEXT NOT NULL,
    part_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO promoter_split (loser, part, part_name) VALUES
    ('rausgegangen-und-bedroomdisco', 'rausgegangen',        'Rausgegangen'),
    ('rausgegangen-und-bedroomdisco', 'bedroomdisco',        'Bedroomdisco'),
    ('drift-puschen',                 'drift',               'Drift'),
    ('drift-puschen',                 'puschen',             'Puschen'),
    ('afro-haus-purple',              'afro-haus',           'Afro Haus'),
    ('afro-haus-purple',              'purple-events',       'Purple Events'),
    ('selecta-g-seven-inch-soldiers', 'selecta-g',           'Selecta G'),
    ('selecta-g-seven-inch-soldiers', 'seven-inch-soldiers', 'Seven Inch Soldiers');

INSERT INTO promoter (name, slug)
SELECT DISTINCT sp.part_name, sp.part
FROM promoter_split sp
WHERE EXISTS (SELECT 1 FROM promoter l WHERE l.slug = sp.loser)
ON CONFLICT (slug) DO NOTHING;

INSERT INTO event_promoter (event_id, promoter_id)
SELECT DISTINCT ep.event_id, p.id
FROM event_promoter ep
JOIN promoter l ON l.id = ep.promoter_id
JOIN promoter_split sp ON sp.loser = l.slug
JOIN promoter p ON p.slug = sp.part
ON CONFLICT (event_id, promoter_id) DO NOTHING;

DELETE FROM promoter l
WHERE l.slug IN (SELECT loser FROM promoter_split);
