-- Four bands SO36 bills with "&" in the name were stored as two acts each (#2414). The parser now
-- keeps them whole, but a past night is never rewritten, so its two links keep both junk rows
-- billed and OrphanArtistSweep never removes them. Pöbel & Gesocks played on 2026-10-03.
--
-- On every night that bills both halves, the whole band takes the first half's place and the
-- second half's slot closes up. A half is deleted only when no other night bills it: `Kai` or
-- `Glory` can be another act at another venue.
--
-- Each band's slug is `slugify` of its name, and its name is what the import bills, both asserted
-- in `MergeSplitSo36AmpersandBandsMigrationTest`, because a row whose slug and name disagree is
-- re-minted by the next import (#1343). A database without the halves is left alone.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TEMPORARY TABLE band_merge (
    slug        TEXT NOT NULL,
    name        TEXT NOT NULL,
    first_slug  TEXT NOT NULL,
    second_slug TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO band_merge (slug, name, first_slug, second_slug) VALUES
    ('pobel-gesocks',                     'Pöbel & Gesocks',                     'pobel', 'gesocks'),
    ('tito-tarantula',                    'Tito & Tarantula',                    'tito',  'tarantula'),
    ('booze-glory',                       'Booze & Glory',                       'booze', 'glory'),
    ('kai-funky-von-ton-steine-scherben', 'Kai & Funky von Ton Steine Scherben', 'kai',   'funky-von-ton-steine-scherben');

-- The nights that bill both halves, with the first half's link and the second half's slot.
CREATE TEMPORARY TABLE split_night ON COMMIT DROP AS
SELECT m.slug, m.name, f.event_id, f.role, f.stage, f.title_derived, f.set_start, f.set_end,
       f.id AS first_link, f.billing_order AS first_order, s.id AS second_link, s.billing_order AS second_order
FROM band_merge m
JOIN artist fa ON fa.slug = m.first_slug
JOIN artist sa ON sa.slug = m.second_slug
JOIN event_artist f ON f.artist_id = fa.id
JOIN event_artist s ON s.artist_id = sa.id AND s.event_id = f.event_id;

-- 1. A band without a row yet is created. The UNIQUE on slug makes the guard exact.
INSERT INTO artist (name, slug)
SELECT DISTINCT n.name, n.slug
FROM split_night n
WHERE NOT EXISTS (SELECT 1 FROM artist e WHERE e.slug = n.slug);

-- 2. The halves' links on those nights, gone.
DELETE FROM event_artist
WHERE id IN (SELECT first_link FROM split_night UNION SELECT second_link FROM split_night);

-- 3. The band, billed at the earlier half's place with the first half's role, stage and set times.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT n.event_id, a.id, n.role, least(n.first_order, n.second_order), n.stage, n.title_derived, n.set_start, n.set_end
FROM split_night n
JOIN artist a ON a.slug = n.slug
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 4. The later half's slot closes up, so the night reads in title order without a gap.
UPDATE event_artist ea
SET billing_order = ea.billing_order - 1
FROM split_night n
WHERE ea.event_id = n.event_id
  AND ea.billing_order > greatest(n.first_order, n.second_order);

-- 5. A half no night bills any more, gone.
DELETE FROM artist a
USING band_merge m
WHERE m.slug IN (SELECT slug FROM split_night)
  AND a.slug IN (m.first_slug, m.second_slug)
  AND NOT EXISTS (SELECT 1 FROM event_artist ea WHERE ea.artist_id = a.id);
