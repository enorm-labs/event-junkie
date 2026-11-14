-- Festsaal credits event 13985 "präsentiert von Helios, Mini, IHK Berlin, Riller & Schnauck". The credit split read the
-- spaced "&" inside the car dealer Riller & Schnauck, and the casing flattened IHK, so the import stored `riller`,
-- `schnauck` and "Ihk Berlin" (#5000). The parser now keeps both whole; this deletes the two halves and renames the third,
-- whose slug an import cannot change. The forced import after deploy creates and links `riller-schnauck`.
--
-- Keyed on slug, and a no-op where a row is absent. A follow names the promoter by id, with no foreign key (V195), so
-- it goes first. Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` before
-- running this (ADR-004).

DELETE FROM accounts.follow f
USING promoter p
WHERE f.kind = 'promoter'
  AND f.entity_id = p.id
  AND p.slug IN ('riller', 'schnauck');

-- The cascade on event_promoter.promoter_id takes their event links.
DELETE FROM promoter
WHERE slug IN ('riller', 'schnauck');

UPDATE promoter
SET name = 'IHK Berlin'
WHERE slug = 'ihk-berlin'
  AND name = 'Ihk Berlin';
