-- Artist rows the de-shouter flattened or split before #2082 (UVB, Joe BRT, DJ TC, CCOSMO, Format:B),
-- and A$AP Rocky, minted before `$` protected its token. A row keeps the name whichever import
-- minted it, so the code fix alone changes nothing that exists. Step 1 restores the display name
-- where the row still carries the minted one, in V055's shape; a slug stays, so no link moves.
--
-- Step 2 folds Matrix's `Deejay` spelling into `DJ`: the scraper now reads both as `DJ`. A row moves
-- onto its new slug where that is free, in V045's shape; where the next import already minted it,
-- the links move and the old row goes.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

-- 1. Display names only.
UPDATE artist a
SET name = r.new_name
FROM (VALUES
    ('a-ap-rocky', 'A$ap Rocky', 'A$AP Rocky'),
    ('uvb', 'Uvb', 'UVB'),
    ('ccosmo', 'Ccosmo', 'CCOSMO'),
    ('joe-brt', 'Joe Brt', 'Joe BRT'),
    ('dj-tc-blactro', 'DJ Tc (Blactro)', 'DJ TC (Blactro)'),
    ('format-b', 'Format:b', 'Format:B')
) AS r(slug, old_name, new_name)
WHERE a.slug = r.slug
  AND a.name = r.old_name;

-- 2a. Where the `DJ` row exists already, it takes the `Deejay` row's billing, then that row goes.
WITH folded(old_slug, new_slug) AS (VALUES
    ('deejay-tc', 'dj-tc'),
    ('deejay-lito-bolton', 'dj-lito-bolton')
)
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, survivor.id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM folded f
JOIN artist loser ON loser.slug = f.old_slug
JOIN artist survivor ON survivor.slug = f.new_slug
JOIN event_artist ea ON ea.artist_id = loser.id
ON CONFLICT (event_id, artist_id) DO NOTHING;

WITH folded(old_slug, new_slug) AS (VALUES
    ('deejay-tc', 'dj-tc'),
    ('deejay-lito-bolton', 'dj-lito-bolton')
)
DELETE FROM artist loser
USING folded f, artist survivor
WHERE loser.slug = f.old_slug
  AND survivor.slug = f.new_slug;

-- 2b. Otherwise the `Deejay` row moves onto the `DJ` slug and name.
UPDATE artist a
SET slug = r.new_slug,
    name = r.new_name
FROM (VALUES
    ('deejay-tc', 'dj-tc', 'DJ TC'),
    ('deejay-lito-bolton', 'dj-lito-bolton', 'DJ Lito Bolton')
) AS r(old_slug, new_slug, new_name)
WHERE a.slug = r.old_slug
  AND NOT EXISTS (SELECT 1 FROM artist t WHERE t.slug = r.new_slug);
