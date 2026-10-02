-- Six artist rows fuse two acts, or two promoters and a night, joined with ` x ` (#2365). The parser
-- now splits the join, but a row keeps the name of the import that minted it, a past night is never
-- rewritten, and OrphanArtistSweep never sees these rows orphaned. So the repair is done here.
--
-- - `Hatebreed x Life Of Agony`, `Tmaro x PapaRaZzle`, `Ifa x Never Back Down`, `Marthe X Pilani
--   Bubu` and Renate's `Fhionn x Cathal` each become their two acts, the names the fixed parser bills.
-- - Urban Spree's `Process Party x Effetto Notte: Hall of Bats with Lovataraxx` becomes Lovataraxx.
--   The two promoters present the night `Hall of Bats`, and its other acts already hold rows.
--
-- `Noah X Petter` stays: the duo releases under that name. The two Urban Spree co-brand rows are
-- V081's.
--
-- Each act's slug is `slugify` of its name, asserted in `SplitXJoinedCoBillsMigrationTest`, because a
-- survivor whose slug and name disagree is re-minted by the next import (#1343). Every step is keyed
-- on the fused row's slug, as V080 did, so a database without it is left alone, and an act that
-- already holds a row is reused.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TEMPORARY TABLE fused_artist (
    slug TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO fused_artist (slug) VALUES
    ('hatebreed-x-life-of-agony'),
    ('tmaro-x-paparazzle'),
    ('ifa-x-never-back-down'),
    ('marthe-x-pilani-bubu'),
    ('fhionn-x-cathal'),
    ('process-party-x-effetto-notte-hall-of-bats-with-lovataraxx');

-- The acts each fused row splits into, in billing order.
CREATE TEMPORARY TABLE split_act (
    fused_slug TEXT NOT NULL,
    slug       TEXT NOT NULL,
    name       TEXT NOT NULL,
    position   INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO split_act (fused_slug, slug, name, position) VALUES
    ('hatebreed-x-life-of-agony',                                  'hatebreed',       'Hatebreed',       0),
    ('hatebreed-x-life-of-agony',                                  'life-of-agony',   'Life Of Agony',   1),
    ('tmaro-x-paparazzle',                                         'tmaro',           'Tmaro',           0),
    ('tmaro-x-paparazzle',                                         'paparazzle',      'PapaRaZzle',      1),
    ('ifa-x-never-back-down',                                      'ifa',             'Ifa',             0),
    ('ifa-x-never-back-down',                                      'never-back-down', 'Never Back Down', 1),
    ('marthe-x-pilani-bubu',                                       'marthe',          'Marthe',          0),
    ('marthe-x-pilani-bubu',                                       'pilani-bubu',     'Pilani Bubu',     1),
    ('fhionn-x-cathal',                                            'fhionn',          'Fhionn',          0),
    ('fhionn-x-cathal',                                            'cathal',          'Cathal',          1),
    ('process-party-x-effetto-notte-hall-of-bats-with-lovataraxx', 'lovataraxx',      'Lovataraxx',      0);

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
