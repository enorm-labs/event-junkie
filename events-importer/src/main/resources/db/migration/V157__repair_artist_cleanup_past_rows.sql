-- The past-night rows the second /artist-cleanup run left (2026-10-14), each judged by the owner. A past night is never
-- imported again, so no parser change reaches them: a placeholder, a night or a programme note minted as an act, an act
-- with a format, a tour or a show fused to it, and acts written into one row. V154's shape for the replacements, so a
-- night that bills a twelve-name row moves every later act down by eleven.
--
-- Keyed on slug and on source, so a database without the row, or a same-slug row another venue bills, is left alone.
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE replacement (
    old_slug      TEXT NOT NULL,
    source_prefix TEXT NOT NULL,
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    position      INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO replacement (old_slug, source_prefix, slug, name, position) VALUES
    ('marcos-coll-album-release-concert',                                                                            'quasimodo:',            'marcos-coll',        'Marcos Coll',        0),
    ('soul-night-ft-frankie-balou',                                                                                  'quasimodo:',            'frankie-balou',      'Frankie Balou',      0),
    ('transllusion-the-opening-of-the-cerebral-gate',                                                                'tresor:',               'transllusion',       'Transllusion',       0),
    ('alisa-nedashkovska-live-4h-aka-travis-or-alice',                                                               'club_der_visionaere:',  'alisa-nedashkovska', 'Alisa Nedashkovska', 0),
    ('dasomazo-more-tba',                                                                                            'void_club:',            'dasomazo',           'DaSoMaZo',           0),
    ('sigur-ros-the-orchestral-tour',                                                                                'uber_eats_music_hall:', 'sigur-ros',          'Sigur Rós',          0),
    ('support-the-baxbys',                                                                                           'metropol:',             'the-baxbys',         'The Baxbys',         0),
    ('jon-rose-hinterland',                                                                                          'morphine:',             'jon-rose',           'Jon Rose',           0),
    ('tina-de',                                                                                                      'renate:',               'tina',               'Tina',               0),
    ('verlegt-29-04-27-m-byrd',                                                                                      'monarch:',              'm-byrd',             'M. Byrd',            0),
    ('guest-dj-from-23-00-alex-kraemer',                                                                             'sonnenraum:',           'alex-kraemer',       'Alex Kraemer',       0),
    ('isabel-van-gelder-eu',                                                                                         'lido:',                 'isabel-van-gelder',  'Isabel Van Gelder',  0),
    ('urso-brass-band-konzert',                                                                                      'sisyphos:',             'urso',               'Urso',               0),
    ('acker-records-showcase-w-pornbugs',                                                                            'klunkerkranich:',       'pornbugs',           'Pornbugs',           0),
    ('aissatou-ms-steak-johnny-circus-show',                                                                         'sisyphos:',             'aissatou',           'Aissatou',           0),
    ('aissatou-ms-steak-johnny-circus-show',                                                                         'sisyphos:',             'ms-steak',           'Ms. Steak',          1),
    ('aissatou-ms-steak-johnny-circus-show',                                                                         'sisyphos:',             'johnny',             'Johnny',             2),
    ('bidar-feat-fem-jam-collective',                                                                                'klunkerkranich:',       'bidar',              'Bîdar',              0),
    ('bidar-feat-fem-jam-collective',                                                                                'klunkerkranich:',       'fem-jam-collective', 'Fem Jam Collective', 1),
    ('boy-deluxe-dream-council',                                                                                     'cassiopeia:',           'boy-deluxe',         'Boy Deluxe',         0),
    ('boy-deluxe-dream-council',                                                                                     'cassiopeia:',           'dream-council',      'Dream Council',      1),
    ('5e7h-vs-bastard-science',                                                                                      'madame_claude:',        '5e7h',               '5e7h',               0),
    ('5e7h-vs-bastard-science',                                                                                      'madame_claude:',        'bastard-science',    'Bastard Science',    1),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'tragik',             'Tragik',             0),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'stempelmann',        'Stempelmann',        1),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'coon',               'Coon',               2),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'jordan',             'Jordan',             3),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'niko-incravalle',    'Niko Incravalle',    4),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'onkel-tom',          'Onkel Tom',          5),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'bad-boy',            'Bad Boy',            6),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'das-amt',            'Das Amt',            7),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'shorty',             'Shorty',             8),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'andy-m',             'Andy M',             9),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'nico-brix',          'Nico Brix',          10),
    ('tragik-stempelmann-coon-jordan-niko-incravalle-onkel-tom-bad-boy-das-amt-shorty-andy-m-nico-brix-andre-wiese', 'der_weisse_hase:',      'andre-wiese',        'André Wiese',        11);

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

-- 2. Make room: a link moves down by one for each two-act row billed before it on its event. One night bills two
--    joined rows (Berghain's Italorama Bar), so the shift is a sum, not a single step.
UPDATE event_artist ea
SET billing_order = ea.billing_order + (
    SELECT sum(g.parts - 1)
    FROM fused_link g
    WHERE g.event_id = ea.event_id
      AND g.billing_order < ea.billing_order
)
WHERE EXISTS (
    SELECT 1
    FROM fused_link g
    WHERE g.event_id = ea.event_id
      AND g.billing_order < ea.billing_order
      AND g.parts > 1
);

-- 3. The acts take the old row's place, shifted the same way, keeping its role, stage, title_derived flag and set times.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT f.event_id, a.id, f.role,
       f.billing_order + r.position + coalesce((
           SELECT sum(g.parts - 1)
           FROM fused_link g
           WHERE g.event_id = f.event_id
             AND g.billing_order < f.billing_order
       ), 0),
       f.stage, f.title_derived, f.set_start, f.set_end
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

-- 5. Rows that name no act, dropped where only their own venue bills them.
SELECT drop_artist_billed_only_by('line-up-tba', 'klunkerkranich:');
SELECT drop_artist_billed_only_by('surprise-guest', 'ms_hoppetosse:');
SELECT drop_artist_billed_only_by('urban-spree-klubnacht-003', 'urban_spree:');
SELECT drop_artist_billed_only_by('urban-spree-klubnacht-004', 'urban_spree:');
SELECT drop_artist_billed_only_by('needs-kritik-der-bedurfnisse', 'tempodrom:');
SELECT drop_artist_billed_only_by('send-your-submissions-to-info-void-club-de', 'void_club:');
SELECT drop_artist_billed_only_by('johnny-knuppel-takeover', 'renate:');
SELECT drop_artist_billed_only_by('mensch-meier-takeover-with', 'renate:');
SELECT drop_artist_billed_only_by('saturday-by-muting-the-noise', 'kater:');
SELECT drop_artist_billed_only_by('sunday-by-257', 'kater:');
SELECT drop_artist_billed_only_by('clubs-im-club-auf-allen-floors', 'sisyphos:');
SELECT drop_artist_billed_only_by('live-electronic-showcase', 'madame_claude:');
SELECT drop_artist_billed_only_by('uk-tour-2026', 'lido:');

-- 6. `Lux.` keeps its slug; only the stray full stop goes.
UPDATE artist
SET name = 'Lux'
WHERE slug = 'lux'
  AND name = 'Lux.';

-- 7. `Saturday Night` is billed nowhere, but an EXACT MusicBrainz match keeps it from the orphan sweep.
DELETE FROM artist a
WHERE a.slug = 'saturday-night'
  AND NOT EXISTS (SELECT 1 FROM event_artist ea WHERE ea.artist_id = a.id);
