-- VOID Club's hard-techno DJ `Vero_` and the Berlin rapper Vero are two acts (data-quality audit, 2026-11-15). The slug
-- generator folds `Vero_` onto `vero`, so SOURCE_ARTIST_SLUG_OVERRIDES now sends VOID Club's billing to `vero-2`. V214 left
-- the stored row at `vero_`, because `vero` was taken. It moves to `vero-2` here and keeps `vero_` as an alias, so its old
-- URL still answers.
--
-- Keyed on slug and name, so a row that is missing or was renamed by hand is left alone. Unqualified table names,
-- deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

INSERT INTO artist_slug_alias (slug, artist_id)
SELECT a.slug, a.id
FROM artist a
WHERE a.slug = 'vero_'
  AND a.name = 'Vero_'
  AND NOT EXISTS (SELECT 1 FROM artist taken WHERE taken.slug = 'vero-2')
ON CONFLICT (slug) DO NOTHING;

UPDATE artist
SET slug       = 'vero-2',
    updated_at = now()
WHERE slug = 'vero_'
  AND name = 'Vero_'
  AND NOT EXISTS (SELECT 1 FROM artist taken WHERE taken.slug = 'vero-2');
