-- Promoter rows that split one promoter or carry a web address as the name (#2653). The
-- normalizer now folds each credit onto the promoter's own name; this moves the rows already
-- stored, because nothing re-imports a past event.
--
-- Admiralspalast credits "Concetbüro Zahlmann" once, and both "Heesen Konzerte GmbH" and "Heesen
-- Media GmbH", two companies of one group whose concert brand is "Heesen Konzerte". Schokoladen
-- credits "Sonic Bomm" once. SO36's presenter calls itself "Berlinmusiker.de". Kulturhaus Peter
-- Edel credits "www.dundj.berlin", the site of the DJ duo Dan & Jensai. Columbia Theater credits
-- the magazine metal.de, a media partner, which the importer now refuses.
--
-- The four-step shape of V031, keyed on slug and a no-op where a row is absent. The promoters no
-- event credits are left to OrphanPromoterSweep. Unqualified table names, deliberately: Flyway sets
-- `search_path` from `spring.flyway.schemas` before running this (ADR-004).

CREATE TEMPORARY TABLE promoter_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO promoter_merge (loser, survivor, survivor_name) VALUES
    ('concetburo-zahlmann', 'concertburo-zahlmann', 'Concertbüro Zahlmann'),
    ('sonic-bomm',          'sonic-boom',           'Sonic Boom'),
    ('heesen',              'heesen-konzerte',      'Heesen Konzerte'),
    ('heesen-media',        'heesen-konzerte',      'Heesen Konzerte'),
    ('berlinmusiker-de',    'berlinmusiker-de',     'Berlinmusiker.de'),
    ('www-dundj-berlin',    'dan-jensai',           'Dan & Jensai');

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

-- 5. The media partner, gone with its links; the events stay.
DELETE FROM promoter
WHERE slug = 'metal-de';
