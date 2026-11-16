-- SO36 credits the booking agency LCC Productions by its domain, `www.Lcc-productions.com`, and the normalizer now folds
-- that onto `LCC Productions`. Nothing re-imports the night, so the stored row changes here.
--
-- The row is renamed in place, so its id and its follows stay. Where the clean slug already exists, the old row's links
-- and follows move onto it and the old row goes. A missing row is a no-op. Unqualified table names, deliberately: Flyway
-- sets `search_path` from `spring.flyway.schemas` (ADR-004).

UPDATE promoter
SET slug = 'lcc-productions', name = 'LCC Productions'
WHERE slug = 'www-lcc-productions-com'
  AND NOT EXISTS (SELECT 1 FROM promoter s WHERE s.slug = 'lcc-productions');

INSERT INTO event_promoter (event_id, promoter_id)
SELECT ep.event_id, s.id
FROM event_promoter ep
JOIN promoter l ON l.id = ep.promoter_id AND l.slug = 'www-lcc-productions-com'
JOIN promoter s ON s.slug = 'lcc-productions'
ON CONFLICT (event_id, promoter_id) DO NOTHING;

UPDATE accounts.follow f
SET entity_id = s.id
FROM promoter l, promoter s
WHERE l.slug = 'www-lcc-productions-com'
  AND s.slug = 'lcc-productions'
  AND f.kind = 'promoter'
  AND f.entity_id = l.id
  AND NOT EXISTS (
      SELECT 1 FROM accounts.follow own
      WHERE own.account_id = f.account_id AND own.kind = 'promoter' AND own.entity_id = s.id
  );

-- A follow names the promoter by id, with no foreign key (V195), so the leftovers go by hand.
DELETE FROM accounts.follow f
USING promoter l
WHERE l.slug = 'www-lcc-productions-com'
  AND f.kind = 'promoter'
  AND f.entity_id = l.id
  AND EXISTS (SELECT 1 FROM promoter s WHERE s.slug = 'lcc-productions');

-- The cascade on event_promoter.promoter_id takes the old row's links.
DELETE FROM promoter l
WHERE l.slug = 'www-lcc-productions-com'
  AND EXISTS (SELECT 1 FROM promoter s WHERE s.slug = 'lcc-productions');
