-- Columbiahalle credits "Greyzone Concerts & Promotion Grey & von Bronikowski GbR" and "Grey & von Bronikowski GbR", the
-- GbR behind Greyzone Concerts. The co-billing split cut both at the spaced "&" before the corrections saw them, and the
-- parts keyed `grey`, `promotion-grey` and `von-bronikowski` (#4916). The normalizer now keeps the credit whole; this
-- moves the rows already stored onto `greyzone-concerts`, because a past event is never imported again.
--
-- The four-step shape of V238, keyed on slug and a no-op where a row is absent, plus V243's step for followers: a follow
-- names the promoter by id, with no foreign key (V195), so nothing else moves it. Unqualified table names, deliberately:
-- Flyway sets `search_path` from `spring.flyway.schemas` before running this (ADR-004).

CREATE TEMPORARY TABLE promoter_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO promoter_merge (loser, survivor, survivor_name) VALUES
    ('grey',            'greyzone-concerts', 'Greyzone Concerts'),
    ('promotion-grey',  'greyzone-concerts', 'Greyzone Concerts'),
    ('von-bronikowski', 'greyzone-concerts', 'Greyzone Concerts');

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
--    both rows, which would trip UNIQUE (event_id, promoter_id) on an update.
INSERT INTO event_promoter (event_id, promoter_id)
SELECT DISTINCT ep.event_id, s.id
FROM event_promoter ep
JOIN promoter l ON l.id = ep.promoter_id
JOIN promoter_merge m ON m.loser = l.slug
JOIN promoter s ON s.slug = m.survivor
WHERE m.loser <> m.survivor
ON CONFLICT (event_id, promoter_id) DO NOTHING;

-- 4. Followers, moved unless the account follows the survivor already. One account can follow two losers, so each
--    account moves its smallest loser follow only, and the delete takes the rest.
UPDATE accounts.follow f
SET entity_id = s.id
FROM promoter l
JOIN promoter_merge m ON m.loser = l.slug AND m.loser <> m.survivor
JOIN promoter s ON s.slug = m.survivor
WHERE f.kind = 'promoter'
  AND f.entity_id = l.id
  AND NOT EXISTS (
      SELECT 1 FROM accounts.follow own
      WHERE own.account_id = f.account_id AND own.kind = 'promoter' AND own.entity_id = s.id
  )
  AND f.id = (
      SELECT o.id FROM accounts.follow o
      JOIN promoter ol ON ol.id = o.entity_id
      JOIN promoter_merge om ON om.loser = ol.slug AND om.survivor = m.survivor AND om.loser <> om.survivor
      WHERE o.account_id = f.account_id AND o.kind = 'promoter'
      ORDER BY ol.slug
      LIMIT 1
  );

DELETE FROM accounts.follow f
USING promoter l
JOIN promoter_merge m ON m.loser = l.slug AND m.loser <> m.survivor
WHERE f.kind = 'promoter'
  AND f.entity_id = l.id
  AND EXISTS (SELECT 1 FROM promoter s WHERE s.slug = m.survivor);

-- 5. The losers, gone. The cascade on event_promoter.promoter_id takes their links.
DELETE FROM promoter l
USING promoter_merge m
WHERE l.slug = m.loser
  AND m.loser <> m.survivor
  AND EXISTS (SELECT 1 FROM promoter s WHERE s.slug = m.survivor);
