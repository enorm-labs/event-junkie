-- The second /artist-cleanup batch: rows a parser minted from a decorated or fused billing, which the parsers now split
-- or strip. A past night is never imported again, so its links move here. Each row is keyed on its slug and on its
-- source, so a database without the row, or a same-slug row another venue bills, is left alone.
--
-- A replacement takes the old row's place at its role, stage and billing order. A row with two acts moves the links
-- after it down by one. An act without a row yet is created. Then the old row goes with drop_artist_billed_only_by.
-- A Discogs number such as `Emma (8)` comes off, so `Emma (1)` and `Emma (8)` become one act. That is accepted.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE replacement (
    old_slug      TEXT NOT NULL,
    source_prefix TEXT NOT NULL,
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    position      INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO replacement (old_slug, source_prefix, slug, name, position) VALUES
    -- Discogs-style numbers.
    ('alina-3',                                   'club_der_visionaere:', 'alina',                        'Alina',                        0),
    ('cez-1',                                     'club_der_visionaere:', 'cez',                          'Cez',                          0),
    ('eliza-2',                                   'club_der_visionaere:', 'eliza',                        'Eliza',                        0),
    ('johnson-2',                                 'club_der_visionaere:', 'johnson',                      'Johnson',                      0),
    ('mfx-1',                                     'club_der_visionaere:', 'mfx',                          'Mfx',                          0),
    ('pily-2',                                    'club_der_visionaere:', 'pily',                         'Pily',                         0),
    ('emma-1',                                    'der_weisse_hase:',     'emma',                         'Emma',                         0),
    ('emma-8',                                    'der_weisse_hase:',     'emma',                         'Emma',                         0),
    ('emma-8-youston',                            'der_weisse_hase:',     'emma',                         'Emma',                         0),
    ('emma-8-youston',                            'der_weisse_hase:',     'youston',                      'Youston',                      1),
    ('zo-1',                                      'der_weisse_hase:',     'zo',                           'Zö',                           0),
    ('jakob-seidensticker-melina-3',              'ms_hoppetosse:',       'jakob-seidensticker-melina',   'Jakob Seidensticker & Melina', 0),
    ('renzo-3',                                   'ms_hoppetosse:',       'renzo',                        'Renzo',                        0),
    ('sisi-2',                                    'ms_hoppetosse:',       'sisi',                         'SiSi',                         0),
    -- A format, a show, a work or a venue fused to the act.
    ('creature-duo-set',                          'crack_bellmer:',       'creature',                     'Creature',                     0),
    ('nicole-m-pikole-drag',                      'crack_bellmer:',       'nicole-m-pikole',              'Nicole M Pikole',              0),
    ('dice-actors-live-on-stage',                 'columbia_theater:',    'dice-actors',                  'Dice Actors',                  0),
    ('guy-offenbach-original-karaoke-band',       'neue_zukunft:',        'guy-offenbach',                'Guy Offenbach',                0),
    ('potsa-lotsa-xl-pizza-lotsa',                'maschinenhaus:',       'potsa-lotsa-xl',               'Potsa Lotsa XL',               0),
    ('robert-henke-presents-dust',                'berghain:',            'robert-henke',                 'Robert Henke',                 0),
    ('shaden-show',                               'tempodrom:',           'shaden',                       'Shaden',                       0),
    ('yok-pocketpunk-spielt-fuck-10-check',       'schokoladen:',         'yok',                          'Yok',                          0),
    ('kneipenabend-ab-20-00',                     'schokoladen:',         'melanie-baker',                'Melanie Baker',                0),
    ('jc-dook-back-at-a-trane',                   'a_trane:',             'jc-dook',                      'JC Dook',                      0),
    ('cfl-aka-krumelur',                          'ritter_butzke:',       'cfl',                          'Cfl',                          0),
    ('inthelinbo-aka-anais-lin',                  'ritter_butzke:',       'inthelinbo',                   'Inthelinbo',                   0),
    ('dj-jonas-poppe-oum-shatt',                  'urban_spree:',         'dj-jonas-poppe',               'DJ Jonas Poppe',               0),
    -- Two acts on one row.
    ('tony-hurdle-invites-tc-the-3rd',            'a_trane:',             'tony-hurdle',                  'Tony Hurdle',                  0),
    ('tony-hurdle-invites-tc-the-3rd',            'a_trane:',             'tc-the-3rd',                   'TC The 3RD',                   1),
    ('vorraum-harba-delta-division-live',         'ohm:',                 'harba',                        'Harba',                        0),
    ('vorraum-harba-delta-division-live',         'ohm:',                 'delta-division',               'Delta Division',               1),
    ('special-guests-kacso-hanga-otis-sandsjo',   'gretchen:',            'kacso-hanga',                  'Kacsó Hanga',                  0),
    ('special-guests-kacso-hanga-otis-sandsjo',   'gretchen:',            'otis-sandsjo',                 'Otis Sandsjö',                 1);

CREATE TEMPORARY TABLE fused_link ON COMMIT DROP AS
SELECT ea.*, a.slug AS old_slug, r.parts
FROM event_artist ea
JOIN artist a ON a.id = ea.artist_id
JOIN event e ON e.id = ea.event_id
JOIN (SELECT old_slug, source_prefix, count(*) AS parts FROM replacement GROUP BY old_slug, source_prefix) r
    ON r.old_slug = a.slug AND starts_with(e.source_id, r.source_prefix);

-- 1. An act without a row yet is created, only when there is a link to replace.
INSERT INTO artist (name, slug)
SELECT DISTINCT ON (r.slug) r.name, r.slug
FROM replacement r
WHERE EXISTS (SELECT 1 FROM fused_link f WHERE f.old_slug = r.old_slug)
  AND NOT EXISTS (SELECT 1 FROM artist a WHERE a.slug = r.slug)
ORDER BY r.slug;

-- 2. Make room: a link billed after a row with two acts moves down by one.
UPDATE event_artist ea
SET billing_order = ea.billing_order + f.parts - 1
FROM fused_link f
WHERE f.parts > 1
  AND ea.event_id = f.event_id
  AND ea.billing_order > f.billing_order;

-- 3. The acts take the old row's place, keeping its role, stage, title_derived flag and set times.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT f.event_id, a.id, f.role, f.billing_order + r.position, f.stage, f.title_derived, f.set_start, f.set_end
FROM fused_link f
JOIN replacement r ON r.old_slug = f.old_slug
JOIN artist a ON a.slug = r.slug
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 4. The replaced links go, then the old rows, unless another venue still bills them.
DELETE FROM event_artist ea
USING fused_link f
WHERE ea.event_id = f.event_id
  AND ea.artist_id = f.artist_id;

SELECT drop_artist_billed_only_by(old_slug, source_prefix)
FROM (SELECT DISTINCT old_slug, source_prefix FROM replacement) old;

-- 5. Rows that name no act: a talk, and a night the parser now reads past to its guests.
SELECT drop_artist_billed_only_by('missverstehen-sie-mich-richtig-robert-habeck-zu-gast-bei-maja-gopel', 'tempodrom:');
SELECT drop_artist_billed_only_by('jazz-night', 'quasimodo:');
