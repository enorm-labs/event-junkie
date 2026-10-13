-- LARK stored its whole organiser field as one promoter: `Homebound Music, BANDU, Bedroomdisco and FluxFM`. The
-- scraper now splits the field into its promoters. A past event is never imported again, so the stored rows are split
-- here: each promoter is created where it is missing and credited on the row's events, and the row goes.
--
-- Keyed on slug and a no-op where a row is absent. The names are the canonical forms the importer stores, so the next
-- import resolves to the same rows. Unqualified table names, deliberately: Flyway sets `search_path` from
-- `spring.flyway.schemas` before running this (ADR-004).

CREATE TEMPORARY TABLE promoter_split (
    fused TEXT NOT NULL,
    slug  TEXT NOT NULL,
    name  TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO promoter_split (fused, slug, name) VALUES
    ('homebound-music-bandu-bedroomdisco-and-fluxfm', 'homebound',    'Homebound'),
    ('homebound-music-bandu-bedroomdisco-and-fluxfm', 'bandu',        'Bandu'),
    ('homebound-music-bandu-bedroomdisco-and-fluxfm', 'bedroomdisco', 'Bedroomdisco'),
    ('homebound-music-bandu-bedroomdisco-and-fluxfm', 'fluxfm',       'FluxFM'),
    ('rausgegangen-bedroomdisco-and-fluxfm',          'rausgegangen', 'Rausgegangen'),
    ('rausgegangen-bedroomdisco-and-fluxfm',          'bedroomdisco', 'Bedroomdisco'),
    ('rausgegangen-bedroomdisco-and-fluxfm',          'fluxfm',       'FluxFM');

-- 1. A promoter without a row yet is created, only when its fused row exists.
INSERT INTO promoter (name, slug)
SELECT DISTINCT s.name, s.slug
FROM promoter_split s
WHERE EXISTS (SELECT 1 FROM promoter f WHERE f.slug = s.fused)
  AND NOT EXISTS (SELECT 1 FROM promoter p WHERE p.slug = s.slug);

-- 2. Each promoter credited on the fused row's events.
INSERT INTO event_promoter (event_id, promoter_id)
SELECT DISTINCT ep.event_id, p.id
FROM event_promoter ep
JOIN promoter f ON f.id = ep.promoter_id
JOIN promoter_split s ON s.fused = f.slug
JOIN promoter p ON p.slug = s.slug
ON CONFLICT (event_id, promoter_id) DO NOTHING;

-- 3. The fused rows, gone with their links.
DELETE FROM promoter
WHERE slug IN (SELECT fused FROM promoter_split);
