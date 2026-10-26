-- Promoter rows that kept a legal form or a long descriptor the normalizer now strips (#3996):
-- "gGmbH", "e.K.", "Veranstaltungsservice" and "Veranstaltungsbetriebs", and the "Schonberg"
-- spelling of Konzertbüro Schoneberg. The normalizer folds each credit onto the promoter's own
-- name; this moves the rows already stored, because nothing re-imports a past event.
--
-- Bossa FM and Konzertbüro Schoneberg already have a row, so the variant merges into it. Reset
-- Production and the Rundfunk-Orchester have none, so the variant is renamed. NXP keeps its slug
-- and gets its capitals back.
--
-- The four-step shape of V103, keyed on slug and a no-op where a row is absent. Unqualified
-- table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE promoter_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO promoter_merge (loser, survivor, survivor_name) VALUES
    ('bossa-fm-veranstaltungsservice',            'bossa-fm',                     'Bossa FM'),
    ('konzertburo-schonberg',                     'konzertburo-schoneberg',       'Konzertbüro Schoneberg'),
    ('reset-production-e-k',                      'reset-production',             'Reset Production'),
    ('rundfunk-orchester-und-chore-ggmbh-berlin', 'rundfunk-orchester-und-chore', 'Rundfunk-Orchester und -Chöre'),
    ('nxp-veranstaltungsbetriebs',                'nxp-veranstaltungsbetriebs',   'NXP Veranstaltungsbetriebs');

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
