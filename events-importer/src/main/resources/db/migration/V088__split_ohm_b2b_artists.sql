-- OHM billed a back-to-back line as one act and a bracketed set note as an act (#2417). The parser
-- now splits at `b2b` and drops the note. A past night is never imported again, and
-- OrphanArtistSweep never sees these rows orphaned, so the repair is done here.
--
-- - Four fused rows each become their two acts, the names the fixed parser bills. `Tafkamp`,
--   `Jesse G` and `Shuray & Walle` already hold rows, which are reused.
-- - `All night long` is deleted outright. `isNonArtistName` rejects it for every source, so no link
--   to it bills a performer.
--
-- Each act's slug is `slugify` of its name, asserted in `SplitOhmB2bArtistsMigrationTest`, because a
-- survivor whose slug and name disagree is re-minted by the next import (#1343). Every step is keyed
-- on the fused row's slug, as V082 did, so a database without it is left alone.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

-- The acts each fused row splits into, in billing order.
CREATE TEMPORARY TABLE split_act (
    fused_slug TEXT NOT NULL,
    slug       TEXT NOT NULL,
    name       TEXT NOT NULL,
    position   INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO split_act (fused_slug, slug, name, position) VALUES
    ('makam-b2b-tafkamp',         'makam',         'Makam',          0),
    ('makam-b2b-tafkamp',         'tafkamp',       'Tafkamp',        1),
    ('jesse-g-b2b-dj-heartbreak', 'jesse-g',       'Jesse G',        0),
    ('jesse-g-b2b-dj-heartbreak', 'dj-heartbreak', 'DJ Heartbreak',  1),
    ('shuray-walle-b2b-naomi',    'shuray-walle',  'Shuray & Walle', 0),
    ('shuray-walle-b2b-naomi',    'naomi',         'Naomi',          1),
    ('amaliah-b2b-niks',          'amaliah',       'Amaliah',        0),
    ('amaliah-b2b-niks',          'niks',          'Niks',           1);

-- Only the fused rows this database has.
CREATE TEMPORARY TABLE present_fused ON COMMIT DROP AS
SELECT a.id, a.slug, (SELECT count(*) FROM split_act s WHERE s.fused_slug = a.slug) AS act_count
FROM artist a
WHERE a.slug IN (SELECT fused_slug FROM split_act);

-- 1. An act without a row yet is created. The UNIQUE on slug makes the guard exact.
INSERT INTO artist (name, slug)
SELECT DISTINCT s.name, s.slug
FROM split_act s
JOIN present_fused p ON p.slug = s.fused_slug
WHERE NOT EXISTS (SELECT 1 FROM artist e WHERE e.slug = s.slug);

-- 2. Make room: a link billed after a fused row moves down by the extra acts that row splits into.
--    The subquery sees the orders as they were before the update.
UPDATE event_artist ea
SET billing_order = ea.billing_order + (
    SELECT sum(p.act_count - 1)
    FROM event_artist x
    JOIN present_fused p ON p.id = x.artist_id
    WHERE x.event_id = ea.event_id
      AND x.billing_order < ea.billing_order
)
WHERE EXISTS (
    SELECT 1
    FROM event_artist x
    JOIN present_fused p ON p.id = x.artist_id
    WHERE x.event_id = ea.event_id
      AND x.billing_order < ea.billing_order
);

-- 3. Every night a fused row billed now bills its acts at its place, keeping the role, stage and
--    set times it carried. An act the night already bills keeps that link.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, act.id, ea.role, ea.billing_order + s.position, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM event_artist ea
JOIN present_fused p ON p.id = ea.artist_id
JOIN split_act s ON s.fused_slug = p.slug
JOIN artist act ON act.slug = s.slug
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 4. The fused rows and the set note, gone. The FK cascade on event_artist removes their links.
DELETE FROM artist
WHERE id IN (SELECT id FROM present_fused)
   OR slug = 'all-night-long';
