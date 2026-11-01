-- The slugger now turns `_` into `-`, as it does every other separator. A stored artist or promoter slug with `_` would
-- no longer match the slug its own name gives, so the next import would create a second row. This moves each such slug
-- to the form the slugger gives now: `dj_dave` to `dj-dave`, `real_o-_-o_it` to `real-o-o-it`.
--
-- An artist keeps its former slug as an alias (#3289). A slug whose new form another row already holds is left as it
-- is, and so is the second of two slugs that fold to one form. Event slugs need nothing: each import rebuilds them.
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE hyphenated ON COMMIT DROP AS
SELECT DISTINCT ON (new_slug) id, slug AS former_slug, new_slug
FROM (SELECT id, slug, btrim(regexp_replace(replace(slug, '_', '-'), '-{2,}', '-', 'g'), '-') AS new_slug
      FROM artist
      WHERE strpos(slug, '_') > 0) candidate
WHERE new_slug <> ''
  AND NOT EXISTS (SELECT 1 FROM artist held WHERE held.slug = candidate.new_slug)
ORDER BY new_slug, id;

UPDATE artist a
SET slug       = h.new_slug,
    updated_at = now()
FROM hyphenated h
WHERE a.id = h.id;

INSERT INTO artist_slug_alias (slug, artist_id)
SELECT former_slug, id
FROM hyphenated
ON CONFLICT (slug) DO NOTHING;

UPDATE promoter p
SET slug       = moved.new_slug,
    updated_at = now()
FROM (SELECT DISTINCT ON (new_slug) id, new_slug
      FROM (SELECT id, btrim(regexp_replace(replace(slug, '_', '-'), '-{2,}', '-', 'g'), '-') AS new_slug
            FROM promoter
            WHERE strpos(slug, '_') > 0) candidate
      WHERE new_slug <> ''
        AND NOT EXISTS (SELECT 1 FROM promoter held WHERE held.slug = candidate.new_slug)
      ORDER BY new_slug, id) moved
WHERE p.id = moved.id;
