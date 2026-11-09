-- Admiralspalast credits "Soul Events". The strip took "Events" as a descriptor, so the row is "Soul".
-- The normalizer now pins the whole name (#4757); this moves the row already stored, because the
-- upsert skips an unchanged event and a past event is never imported again.
--
-- The shape of V076, keyed on slug and the old name, so a row a person corrected since is left alone.
-- When `soul-events` already exists, the links move to it and the old row goes. Otherwise the old row
-- takes the new name and slug, and keeps its id, its event links and its follows. Unqualified table
-- names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` before running this (ADR-004).

INSERT INTO event_promoter (event_id, promoter_id)
SELECT ep.event_id, survivor.id
FROM promoter loser
JOIN promoter survivor ON survivor.slug = 'soul-events'
JOIN event_promoter ep ON ep.promoter_id = loser.id
WHERE loser.slug = 'soul'
  AND loser.name = 'Soul'
ON CONFLICT (event_id, promoter_id) DO NOTHING;

-- The cascade on event_promoter.promoter_id takes the old row's links.
DELETE FROM promoter loser
USING promoter survivor
WHERE loser.slug = 'soul'
  AND loser.name = 'Soul'
  AND survivor.slug = 'soul-events';

UPDATE promoter
SET name = 'Soul Events',
    slug = 'soul-events'
WHERE slug = 'soul'
  AND name = 'Soul'
  AND NOT EXISTS (SELECT 1 FROM promoter WHERE slug = 'soul-events');
