-- Privatclub credits Cosmopolitan "präsentiert von Duct Tape × Anytime", two collectives. The co-billing split read only a
-- spaced "&", so the import stored one promoter, `duct-tape-anytime` (#4917). The normalizer now splits at a spaced "×";
-- this deletes the joint row, and the forced import after deploy creates and links `duct-tape` and `anytime`.
--
-- Keyed on slug, and a no-op where the row is absent. A follow names the promoter by id, with no foreign key (V195), so
-- it goes first. Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` before
-- running this (ADR-004).

DELETE FROM accounts.follow f
USING promoter p
WHERE f.kind = 'promoter'
  AND f.entity_id = p.id
  AND p.slug = 'duct-tape-anytime';

-- The cascade on event_promoter.promoter_id takes its event links.
DELETE FROM promoter
WHERE slug = 'duct-tape-anytime';
