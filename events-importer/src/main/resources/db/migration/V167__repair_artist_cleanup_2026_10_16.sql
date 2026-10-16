-- The third /artist-cleanup run (2026-10-16), each row judged by the owner: an act with a set name, a set length, a show,
-- a work or a tagline fused to it, two acts on one row, a night or a programme minted as an act, and two DJs stored twice.
-- The parsers now split or strip most of these, but a past night is never imported again, so its links move here. V157's
-- shape for the replacements, V152's for the merges.
--
-- Keyed on slug, and a replacement or a drop on its source too, so a database without the row, or a same-slug row another
-- venue bills, is left alone. Unqualified table names, deliberately: Flyway sets `search_path` from
-- `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE replacement (
    old_slug      TEXT NOT NULL,
    source_prefix TEXT NOT NULL,
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    position      INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO replacement (old_slug, source_prefix, slug, name, position) VALUES
    ('dj-gomez',                              'duncker:',           'gomez',                   'Gomez',                   0),
    ('hanzel-efetto-notte',                   'duncker:',           'hanzel',                  'Hanzel',                  0),
    ('ethereal-logic-all-night-long',         'ms_hoppetosse:',     'ethereal-logic',          'Ethereal Logic',          0),
    ('bad-sound-system-feat-fermin-muguruza', 'clash:',             'bad-sound-system',        'Bad Sound System',        0),
    ('bad-sound-system-feat-fermin-muguruza', 'clash:',             'fermin-muguruza',         'Fermin Muguruza',         1),
    ('das-kinn-sonic-morgue',                 'silent_green:',      'das-kinn',                'Das Kinn',                0),
    ('lacrimosa-mit-orchester-einzige',       'admiralspalast:',    'lacrimosa',               'Lacrimosa',               0),
    ('stegreif-orchester-freeeroica-1',       'saalchen:',          'stegreif-orchester',      'Stegreif Orchester',      0),
    ('stegreif-orchester-freeeroica-2',       'saalchen:',          'stegreif-orchester',      'Stegreif Orchester',      0),
    ('polak-friendly-fire',                   'tempodrom:',         'oliver-polak',            'Oliver Polak',            0),
    ('evelyn-plaschg-alphabet',               'morphine:',          'evelyn-plaschg',          'Evelyn Plaschg',          0),
    ('im-anschlusz-constant-disappointment',  'neue_zukunft:',      'constant-disappointment', 'Constant Disappointment', 0),
    -- `Nawaya` is the piece. The venue's page credits the Ramal Ensemble.
    ('nawaya-absichten',                      'theater_im_delphi:', 'ramal-ensemble',          'Ramal Ensemble',          0),
    ('skarface-est-1991-la-france',           'supamolly:',         'skarface',                'Skarface',                0),
    ('dj-tc-blactro',                         'matrix:',            'dj-tc',                   'DJ TC',                   0),
    ('dj-r2v-kiss-fm',                        'matrix:',            'dj-r2v',                  'DJ R2V',                  0);

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

-- 2. Make room: a link moves down by one for each two-act row billed before it on its event.
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
SELECT drop_artist_billed_only_by('spntaneous', 'crack_bellmer:');
SELECT drop_artist_billed_only_by('methods-of-dance-2-act', 'eschschloraque:');
SELECT drop_artist_billed_only_by('the-i-in-the-mirror-reflection', 'silent_green:');
SELECT drop_artist_billed_only_by('vier-pianisten-ein-konzert', 'admiralspalast:');
SELECT drop_artist_billed_only_by('exklusive-orchester-show-in-europa', 'admiralspalast:');
SELECT drop_artist_billed_only_by('party101', 'metropol:');
SELECT drop_artist_billed_only_by('freak-on-a-leash-unleashed', 'badehaus:');

-- 6. Two DJs stored twice. NAME_CORRECTIONS and Matrix's `D.J.` rule now fold each spelling onto one.
CREATE TEMPORARY TABLE artist_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_merge (loser, survivor, survivor_name) VALUES
    ('dj-don-rogall', 'don-rogall', 'Don Rogall'),
    ('d-j-r2v',       'dj-r2v',     'DJ R2V');

INSERT INTO artist (name, slug)
SELECT m.survivor_name, m.survivor
FROM artist_merge m
WHERE EXISTS (SELECT 1 FROM artist l WHERE l.slug = m.loser)
  AND NOT EXISTS (SELECT 1 FROM artist s WHERE s.slug = m.survivor);

INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, s.id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM event_artist ea
JOIN artist l ON l.id = ea.artist_id
JOIN artist_merge m ON m.loser = l.slug
JOIN artist s ON s.slug = m.survivor
ON CONFLICT (event_id, artist_id) DO NOTHING;

DELETE FROM artist l
USING artist_merge m
WHERE l.slug = m.loser
  AND EXISTS (SELECT 1 FROM artist s WHERE s.slug = m.survivor);

-- 7. `(Unauffinbar)` keeps its slug; only the brackets go.
UPDATE artist
SET name = 'Unauffinbar'
WHERE slug = 'unauffinbar'
  AND name = '(Unauffinbar)';

-- 8. `gomez` is a Duncker resident DJ, but the sweep matched it to the English indie band Gomez, and the enrichment copied
-- the band's text, image, links and facts onto it. AMBIGUOUS queues it for review: the lookup sweep asks again only for
-- UNCHECKED or a rename after `musicbrainz_checked_at`, and the Discogs sweep only for NONE. Guarded on the band's MBID,
-- so a row already corrected is left alone.
UPDATE artist
SET musicbrainz_id = NULL,
    musicbrainz_match = 'AMBIGUOUS',
    musicbrainz_enriched_at = NULL,
    description = NULL,
    description_language = NULL,
    description_attribution = NULL,
    description_licence_id = NULL,
    description_source_url = NULL,
    description_alt = NULL,
    description_alt_language = NULL,
    description_alt_attribution = NULL,
    description_alt_licence_id = NULL,
    description_alt_source_url = NULL,
    image_url = NULL,
    image_attribution = NULL,
    image_licence_id = NULL,
    image_source_url = NULL,
    website_url = NULL,
    facebook_url = NULL,
    instagram_url = NULL,
    youtube_url = NULL,
    bandcamp_url = NULL,
    soundcloud_url = NULL,
    discogs_url = NULL,
    wikidata_url = NULL,
    resident_advisor_url = NULL,
    spotify_url = NULL,
    artist_type = NULL,
    founded = NULL,
    founded_in = NULL,
    country = NULL,
    comedian = NULL,
    discogs_id = NULL,
    discogs_match = 'UNCHECKED',
    discogs_checked_at = NULL
WHERE slug = 'gomez'
  AND musicbrainz_id = '304cdd9d-a04e-4eb2-865c-980a553a0efd';
