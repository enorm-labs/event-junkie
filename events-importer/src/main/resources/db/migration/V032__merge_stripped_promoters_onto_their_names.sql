-- Five credits lost their descriptor to the strip and were then read as fragments: "Channel Music"
-- stored as `channel`, "ITD Events" as `itd`, "MFP Concerts" as `mfp`, "Spirit Events" as `spirit`,
-- "ACT agency" as `act`, "LEASING&RENT OÜ" as `leasing-rent` (#1361). V026 dropped the rows that
-- existed then and the next import minted them again. The normalizer now keeps the descriptor on
-- an initialism and pins the rest; this moves the rows that exist onto the slugs of those names.
-- The same four-step shape as V029, keyed on slug and a no-op where a row is absent.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TEMPORARY TABLE promoter_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO promoter_merge (loser, survivor, survivor_name) VALUES
    ('act',          'act-agency',      'ACT Agency'),
    ('channel',      'channel-music',   'Channel Music'),
    ('itd',          'itd-events',      'ITD Events'),
    ('mfp',          'mfp-concerts',    'MFP Concerts'),
    ('spirit',       'spirit-events',   'Spirit Events'),
    ('leasing-rent', 'leasing-rent-ou', 'LEASING&RENT OÜ');

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

-- 3. The losers' events, moved.
UPDATE event_promoter ep
SET promoter_id = s.id
FROM promoter_merge m
JOIN promoter l ON l.slug = m.loser
JOIN promoter s ON s.slug = m.survivor
WHERE m.loser <> m.survivor
  AND ep.promoter_id = l.id
  AND NOT EXISTS (
      SELECT 1 FROM event_promoter d
      WHERE d.event_id = ep.event_id AND d.promoter_id = s.id
  );

-- 4. The losers, gone.
DELETE FROM promoter l
USING promoter_merge m
WHERE l.slug = m.loser
  AND m.loser <> m.survivor
  AND EXISTS (SELECT 1 FROM promoter s WHERE s.slug = m.survivor);
