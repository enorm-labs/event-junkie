-- Two promoter rows that name no promoter as stored (#2331).
--
-- Admiralspalast credits "„Aufgeigen.at“ Künstler und Veranstaltungs GmbH". The strip took the
-- legal form and left the quotes and "Künstler und", stored as `aufgeigen-at-kunstler-und`. The
-- normalizer now drops both and pins "Aufgeigen.at"; this moves the row onto that name's slug, in
-- the shape of V032.
--
-- V026 deleted `das-forgotten-female-composers`. Its guard then read the whole credit, and Insel's
-- "Das forgotten female* composers e.V." was not on the list, so the next import minted the row
-- again. #1361 closed the guard but deleted nothing, and the row stayed public with no events.
--
-- Keyed on slug and a no-op where a row is absent. Unqualified table names, deliberately: Flyway
-- sets `search_path` from `spring.flyway.schemas` before running this (ADR-004).

CREATE TEMPORARY TABLE promoter_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO promoter_merge (loser, survivor, survivor_name) VALUES
    ('aufgeigen-at-kunstler-und', 'aufgeigen-at', 'Aufgeigen.at');

-- 1. A missing survivor is the loser, renamed.
UPDATE promoter p
SET slug = m.survivor, name = m.survivor_name
FROM promoter_merge m
WHERE p.slug = m.loser
  AND NOT EXISTS (SELECT 1 FROM promoter s WHERE s.slug = m.survivor);

-- 2. The display name, on a survivor that exists.
UPDATE promoter p
SET name = m.survivor_name
FROM promoter_merge m
WHERE p.slug = m.survivor
  AND p.name <> m.survivor_name;

-- 3. The loser's events, linked to an existing survivor.
INSERT INTO event_promoter (event_id, promoter_id)
SELECT DISTINCT ep.event_id, s.id
FROM event_promoter ep
JOIN promoter l ON l.id = ep.promoter_id
JOIN promoter_merge m ON m.loser = l.slug
JOIN promoter s ON s.slug = m.survivor
ON CONFLICT (event_id, promoter_id) DO NOTHING;

-- 4. The loser, gone where a survivor exists. The cascade on event_promoter.promoter_id takes its
--    links.
DELETE FROM promoter l
USING promoter_merge m
WHERE l.slug = m.loser
  AND EXISTS (SELECT 1 FROM promoter s WHERE s.slug = m.survivor);

-- 5. The fragment, gone with its links; the events stay.
DELETE FROM promoter
WHERE slug = 'das-forgotten-female-composers';
