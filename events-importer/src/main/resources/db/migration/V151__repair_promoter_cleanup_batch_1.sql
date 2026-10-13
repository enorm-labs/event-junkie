-- The first promoter /artist-cleanup batch. The normalizer now folds three spacing twins and two company forms onto one
-- promoter, refuses five names that are a party, a show or a performer, and the import drops a venue credited as the
-- promoter of its own nights. A past event is never imported again, so this repairs the rows already stored.
--
-- The merge is V103's shape, keyed on slug and a no-op where a row is absent. The unlinked rows are left to
-- OrphanPromoterSweep. Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TEMPORARY TABLE promoter_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO promoter_merge (loser, survivor, survivor_name) VALUES
    ('d2mberlin',                                  'd2m-berlin',          'd2m berlin'),
    ('crunchtapes',                                'crunch-tapes',        'Crunch Tapes'),
    ('tingel-tangel',                              'tingeltangel',        'TingelTangel'),
    ('concert-concept-veranstaltungs-gmbh-berlin', 'concert-concept',     'Concert Concept'),
    ('entertain-the-world-etw-management',         'entertain-the-world', 'Entertain the World');

-- 1. A missing survivor is the smallest of its losers that exists, renamed.
UPDATE promoter p
SET slug = m.survivor, name = m.survivor_name
FROM (
    SELECT survivor, survivor_name, MIN(loser) AS loser
    FROM promoter_merge
    WHERE EXISTS (SELECT 1 FROM promoter l WHERE l.slug = promoter_merge.loser)
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

-- 3. The losers' events, linked to the survivor; an event can already credit both.
INSERT INTO event_promoter (event_id, promoter_id)
SELECT DISTINCT ep.event_id, s.id
FROM event_promoter ep
JOIN promoter l ON l.id = ep.promoter_id
JOIN promoter_merge m ON m.loser = l.slug
JOIN promoter s ON s.slug = m.survivor
ON CONFLICT (event_id, promoter_id) DO NOTHING;

-- 4. The losers, gone with their links.
DELETE FROM promoter l
USING promoter_merge m
WHERE l.slug = m.loser
  AND EXISTS (SELECT 1 FROM promoter s WHERE s.slug = m.survivor);

-- 5. Names that are no promoter, unlinked from every event.
DELETE FROM event_promoter ep
USING promoter p
WHERE p.id = ep.promoter_id
  AND p.slug IN (
      'dirty-dancing-party',
      'fandom-night',
      'piano-man-thomas-botig',
      'adults-only-comedy-by-anna-beros',
      'process-party-effetto-notte'
  );

-- 6. A venue credited as the promoter of its own night, unlinked from that night only.
DELETE FROM event_promoter ep
USING event e, venue v, promoter p
WHERE e.id = ep.event_id
  AND v.id = e.venue_id
  AND p.id = ep.promoter_id
  AND p.slug = v.slug;
