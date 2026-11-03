-- The /artist-cleanup run of 2026-11-03, the bracket half: a line-up's crew, label, home town, real name or set note
-- in brackets after the act, the members a bracket names, an initialism's expansion, and a record label billed as an
-- act. The parser no longer makes them: `stripLineupBracket` in the line-up scrapers, the members and initialism rules in
-- `stripArtistSuffix`, and the record label in `isNonArtistName`. A past night is never imported again, so the stored
-- rows change here.
--
-- The V150 shape, keyed on the old slug and the source, so a same-slug row another venue bills keeps its link.
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE replacement (
    old_slug      TEXT NOT NULL,
    source_prefix TEXT NOT NULL,
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    position      INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO replacement (old_slug, source_prefix, slug, name, position) VALUES
    -- A crew in square brackets.
    ('coco-cobra-queerpool',                         'renate:',              'coco-cobra',              'Coco Cobra',              0),
    ('manolo-body-language',                         'renate:',              'manolo',                  'Manolo.',                 0),
    ('organza-elsi',                                 'renate:',              'organza',                 'Organza',                 0),
    ('panooc-xoxa',                                  'renate:',              'panooc',                  'Panooc',                  0),
    ('robin-flux-spectra',                           'renate:',              'robin-flux',              'Robin Flux',              0),
    -- A crew, label, home town, real name or set note in round brackets.
    ('allen-basse-frequenze',                        'club_der_visionaere:', 'allen',                   'Allen',                   0),
    ('finnman-basse-frequenze',                      'club_der_visionaere:', 'finnman',                 'Finnman',                 0),
    ('maria-wildeis-tc80-live-2h',                   'club_der_visionaere:', 'maria-wildeis',           'Maria Wildeis',           0),
    ('levandream-garderobe',                         'minimal_bar:',         'levandream',              'Levandream',              0),
    ('moksalesi-garderobe',                          'minimal_bar:',         'moksalesi',               'Moksalesi',               0),
    ('dj-burdi-call-brudi-love',                     'minimal_bar:',         'dj-burdi-call',           'DJ Burdi Call',           0),
    ('hitomi-an-toi',                                'berghain:',            'hitomi',                  'Hitomi',                  0),
    ('luca-saporito-audiofly',                       'kater:',               'luca-saporito',           'Luca Saporito',           0),
    ('serokolo-7-nyege-nyege',                       'aeden:',               'serokolo-7',              'Serokolo 7',              0),
    ('molly-on-molly',                               'aeden:',               'molly',                   'mølly',                   0),
    ('siousie-leipzig',                              'beate_uwe:',           'siousie',                 'Siousie',                 0),
    ('sol-angel-colombia-madrid',                    'lark:',                'sol-angel',               'Sol Angel',               0),
    ('suzueri-elico-suzuki',                         'morphine:',            'suzueri',                 'suzueri',                 0),
    ('pauline-murray-penetration',                   'columbia_theater:',    'pauline-murray',          'Pauline Murray',          0),
    ('saida-makhmudzade-dance-movement',             'paloma:',              'saida-makhmudzade',       'Saida Makhmudzade',       0),
    -- The members in brackets.
    ('frosa-sige-alarcon-frolar',                    'acud_macht_neu:',      'frosa',                   'FRØSA',                   0),
    ('seahorse-dj-seaview-wendy',                    'acud_macht_neu:',      'seahorse',                'Seahorse',                0),
    ('spice-boys-carpenter-effect-zylenth',          'beate_uwe:',           'spice-boys',              'Spice Boys',              0),
    ('ursula-k-le-gunfingers-80hdj-and-otto-vlotto', 'crack_bellmer:',       'ursula-k-le-gunfingers',  'Ursula K. Le Gunfingers', 0),
    ('futschi-fako-kaufmann-leon-licht',             'sisyphos:',            'futschi-fako',            'Futschi & Fako',          0),
    ('spicy-lab-vco-thirteen-doze',                  'ohm:',                 'spicy-lab',               'Spicy Lab',               0),
    ('the-evil-b-side-twins-dj-tool-yazzus',         'ohm:',                 'the-evil-b-side-twins',   'The Evil B-Side Twins',   0),
    ('melange-quartett-mel-michel-band',             'speiches:',            'melange-quartett',        'Melange Quartett',        0),
    ('waffel-delirium-annawaffel-b2b-disko-delirium', 'jonny_knuppel:',      'waffel-delirium',         'WAFFEL Delirium',         0),
    -- What the initialism stands for.
    ('lmp-lesmainspopulaires',                       'klunkerkranich:',      'lmp',                     'LMP',                     0),
    ('k-a-o-s-kein-abend-ohne-schnaps',              'so36:',                'k-a-o-s',                 'K.A.O.S.',                0);

CREATE TEMPORARY TABLE replaced_link ON COMMIT DROP AS
SELECT ea.*, a.slug AS old_slug
FROM event_artist ea
JOIN artist a ON a.id = ea.artist_id
JOIN event e ON e.id = ea.event_id
JOIN replacement r ON r.old_slug = a.slug AND starts_with(e.source_id, r.source_prefix);

INSERT INTO artist (name, slug)
SELECT r.name, r.slug
FROM replacement r
WHERE EXISTS (SELECT 1 FROM replaced_link l WHERE l.old_slug = r.old_slug)
  AND NOT EXISTS (SELECT 1 FROM artist a WHERE a.slug = r.slug);

INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT l.event_id, a.id, l.role, l.billing_order + r.position, l.stage, l.title_derived, l.set_start, l.set_end
FROM replaced_link l
JOIN replacement r ON r.old_slug = l.old_slug
JOIN artist a ON a.slug = r.slug
ON CONFLICT (event_id, artist_id) DO NOTHING;

DELETE FROM event_artist ea
USING replaced_link l
WHERE ea.event_id = l.event_id
  AND ea.artist_id = l.artist_id;

-- Followers of a replaced row move onto the act, unless the account follows it already. A follow names the artist by
-- id, with no foreign key (V195), so nothing else moves it.
CREATE TEMPORARY TABLE artist_move ON COMMIT DROP AS
SELECT DISTINCT o.id AS loser_id, a.id AS survivor_id
FROM replacement r
JOIN artist o ON o.slug = r.old_slug
JOIN artist a ON a.slug = r.slug
WHERE EXISTS (SELECT 1 FROM replaced_link l WHERE l.artist_id = o.id)
  AND NOT EXISTS (SELECT 1 FROM event_artist ea WHERE ea.artist_id = o.id);

UPDATE accounts.follow f
SET entity_id = mv.survivor_id
FROM artist_move mv
WHERE f.kind = 'artist'
  AND f.entity_id = mv.loser_id
  AND NOT EXISTS (
      SELECT 1 FROM accounts.follow own
      WHERE own.account_id = f.account_id AND own.kind = 'artist' AND own.entity_id = mv.survivor_id
  );

DELETE FROM accounts.follow f
USING artist_move mv
WHERE f.kind = 'artist'
  AND f.entity_id = mv.loser_id;

SELECT drop_artist_billed_only_by(old_slug, source_prefix) FROM replacement;

-- Two record labels billed in a line-up.
SELECT drop_artist_billed_only_by('lada-recordings', 'golden_gate:');
SELECT drop_artist_billed_only_by('puzzekazze-records', 'lux:');
