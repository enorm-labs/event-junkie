-- sisy.fan marks a set with `*KONZERT*`, `*Hybrid*` or `*Liveact*`, and the parser stored the marker as part of the act
-- (#3938). It also stored a lone `*Secret` slot as an act. The scraper now strips the markers and bills nothing for a
-- lone one, but the three weekends were past before the fix shipped, and a past weekend is never imported again.
--
-- V196 dropped the four `*Konzert` rows, and with them their links. The owner chose a relink instead, so this restores
-- each act under its clean name, at the role, billing order, stage and set times production billed. It also relinks
-- the `*Hybrid` and `*Liveact` rows in V150's shape and drops `*Secret`. Every step is keyed on the slug and the
-- source, and an event that already bills the clean act keeps its own link, so a second run changes nothing.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE replacement (
    old_slug      TEXT NOT NULL,
    source_prefix TEXT NOT NULL,
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL
) ON COMMIT DROP;

-- The four V196 dropped are listed too: on a database where another venue still billed one, V196 kept it.
INSERT INTO replacement (old_slug, source_prefix, slug, name) VALUES
    ('christopher-coe-hybrid',  'sisyphos:', 'christopher-coe', 'Christopher Coe'),
    ('chow-chow-disco-liveact', 'sisyphos:', 'chow-chow-disco', 'Chow Chow Disco'),
    ('suboptimal-dub-konzert',  'sisyphos:', 'suboptimal-dub',  'Suboptimal Dub'),
    ('pavelo-schnell-konzert',  'sisyphos:', 'pavelo-schnell',  'Pavelo & Schnell'),
    ('matze-sampler-konzert',   'sisyphos:', 'matze-sampler',   'Matze Sampler'),
    ('tasheeno-konzert',        'sisyphos:', 'tasheeno',        'Tasheeno');

-- The links V196 removed, as production billed them.
CREATE TEMPORARY TABLE dropped_link (
    source_id     TEXT        NOT NULL,
    slug          TEXT        NOT NULL,
    name          TEXT        NOT NULL,
    billing_order INT         NOT NULL,
    stage         TEXT        NOT NULL,
    set_start     TIMESTAMPTZ NOT NULL,
    set_end       TIMESTAMPTZ NOT NULL
) ON COMMIT DROP;

INSERT INTO dropped_link (source_id, slug, name, billing_order, stage, set_start, set_end) VALUES
    ('sisyphos:2026-10-16', 'suboptimal-dub', 'Suboptimal Dub',   43, 'Dampfer 🎶',  '2026-10-18T22:00:00+02:00', '2026-10-19T01:00:00+02:00'),
    ('sisyphos:2026-10-16', 'pavelo-schnell', 'Pavelo & Schnell', 46, 'Lord Inn 🎶', '2026-10-17T23:00:00+02:00', '2026-10-18T01:00:00+02:00'),
    ('sisyphos:2026-10-23', 'matze-sampler',  'Matze Sampler',    43, 'Lord Inn 🎶', '2026-10-24T23:00:00+02:00', '2026-10-25T01:00:00+02:00'),
    ('sisyphos:2026-10-23', 'tasheeno',       'Tasheeno',         46, 'Lord Inn 🎶', '2026-10-25T22:30:00+01:00', '2026-10-26T00:00:00+01:00');

CREATE TEMPORARY TABLE fused_link ON COMMIT DROP AS
SELECT ea.*, a.slug AS old_slug
FROM event_artist ea
JOIN artist a ON a.id = ea.artist_id
JOIN event e ON e.id = ea.event_id
JOIN replacement r ON r.old_slug = a.slug AND starts_with(e.source_id, r.source_prefix);

-- 1. An act without a row yet is created, only when there is a link to replace or restore.
INSERT INTO artist (name, slug)
SELECT DISTINCT ON (c.slug) c.name, c.slug
FROM (
    SELECT r.slug, r.name FROM replacement r WHERE EXISTS (SELECT 1 FROM fused_link f WHERE f.old_slug = r.old_slug)
    UNION ALL
    SELECT d.slug, d.name FROM dropped_link d WHERE EXISTS (SELECT 1 FROM event e WHERE e.source_id = d.source_id)
) c
WHERE NOT EXISTS (SELECT 1 FROM artist a WHERE a.slug = c.slug)
ORDER BY c.slug;

-- 2. The act takes a marked row's place, keeping its role, billing order, stage, title_derived flag and set times.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT f.event_id, a.id, f.role, f.billing_order, f.stage, f.title_derived, f.set_start, f.set_end
FROM fused_link f
JOIN replacement r ON r.old_slug = f.old_slug
JOIN artist a ON a.slug = r.slug
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 3. The links V196 removed come back. An event that already bills the act keeps its own link.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, set_start, set_end)
SELECT e.id, a.id, 'HEADLINER', d.billing_order, d.stage, d.set_start, d.set_end
FROM dropped_link d
JOIN event e ON e.source_id = d.source_id
JOIN artist a ON a.slug = d.slug
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 4. The replaced links go, then the marked rows, unless another venue still bills them.
DELETE FROM event_artist ea
USING fused_link f
WHERE ea.event_id = f.event_id
  AND ea.artist_id = f.artist_id;

SELECT drop_artist_billed_only_by(old_slug, source_prefix)
FROM replacement;

-- 5. `*Secret` names no act.
SELECT drop_artist_billed_only_by('secret', 'sisyphos:');
