-- Four artist rows minted between 2026-09-25 and 09-27 name no act, or fuse several (#2330). The
-- parser fixes are merged (#2068, #2071), but each row bills one past night: an import rewrites
-- upcoming rows only, there is no re-seed on staging or production, and OrphanArtistSweep never
-- sees them orphaned. So the repair is done here.
--
-- - `(Th)ink About That` is SO36's tattoo convention, not an act. It goes, and the night keeps no
--   artist, which is what the fixed parser bills for it.
-- - LARK's `Hum w/ Kyle Hall b2b K15` and `Mamalia’The first lady of modern funk’ft.Mauricio Fleury`
--   become Kyle Hall, K15, Mamalia and Mauricio Fleury, the names and order the fixed parser bills.
-- - Ritter Butzke's `Gloria Game Boyz FEAT. Vero` becomes Gloria Game Boyz and Vero. `vero` already
--   holds a row, so it is reused rather than duplicated, as the importer would do by slug.
--
-- `The Groovy Cellar` (#2330 lists it too) stays: it is a band, which the venue page and its
-- MusicBrainz match both confirm.
--
-- Each act's slug is `slugify` of its name, asserted in `SplitSeptemberJunkArtistsMigrationTest`,
-- because a survivor whose slug and name disagree is re-minted by the next import (#1343). Every
-- step is keyed on the fused row's slug, as V059 did, so a database without it is left alone.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TEMPORARY TABLE fused_artist (
    slug TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO fused_artist (slug) VALUES
    ('th-ink-about-that'),
    ('hum-w-kyle-hall-b2b-k15'),
    ('mamaliathe-first-lady-of-modern-funkft-mauricio-fleury'),
    ('gloria-game-boyz-feat-vero');

-- The acts each fused row splits into, in billing order. The convention splits into none.
CREATE TEMPORARY TABLE split_act (
    fused_slug TEXT NOT NULL,
    slug       TEXT NOT NULL,
    name       TEXT NOT NULL,
    position   INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO split_act (fused_slug, slug, name, position) VALUES
    ('hum-w-kyle-hall-b2b-k15',                                'kyle-hall',        'Kyle Hall',        0),
    ('hum-w-kyle-hall-b2b-k15',                                'k15',              'K15',              1),
    ('mamaliathe-first-lady-of-modern-funkft-mauricio-fleury', 'mamalia',          'Mamalia',          0),
    ('mamaliathe-first-lady-of-modern-funkft-mauricio-fleury', 'mauricio-fleury',  'Mauricio Fleury',  1),
    ('gloria-game-boyz-feat-vero',                             'gloria-game-boyz', 'Gloria Game Boyz', 0),
    ('gloria-game-boyz-feat-vero',                             'vero',             'Vero',             1);

-- Only the fused rows this database has.
CREATE TEMPORARY TABLE present_fused ON COMMIT DROP AS
SELECT a.id, a.slug, (SELECT count(*) FROM split_act s WHERE s.fused_slug = a.slug) AS act_count
FROM artist a
JOIN fused_artist f ON f.slug = a.slug;

-- 1. An act without a row yet is created. The UNIQUE on slug makes the guard exact.
INSERT INTO artist (name, slug)
SELECT DISTINCT s.name, s.slug
FROM split_act s
JOIN present_fused p ON p.slug = s.fused_slug
WHERE NOT EXISTS (SELECT 1 FROM artist e WHERE e.slug = s.slug);

-- 2. Make room: a link billed after a fused row moves down by the extra acts that row splits into,
--    so the night reads in title order. The subquery sees the orders as they were before the update.
UPDATE event_artist ea
SET billing_order = ea.billing_order + (
    SELECT sum(p.act_count - 1)
    FROM event_artist x
    JOIN present_fused p ON p.id = x.artist_id
    WHERE x.event_id = ea.event_id
      AND x.billing_order < ea.billing_order
      AND p.act_count > 1
)
WHERE EXISTS (
    SELECT 1
    FROM event_artist x
    JOIN present_fused p ON p.id = x.artist_id
    WHERE x.event_id = ea.event_id
      AND x.billing_order < ea.billing_order
      AND p.act_count > 1
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

-- 4. The fused rows, gone. The FK cascade on event_artist removes their links.
DELETE FROM artist
WHERE id IN (SELECT id FROM present_fused);
