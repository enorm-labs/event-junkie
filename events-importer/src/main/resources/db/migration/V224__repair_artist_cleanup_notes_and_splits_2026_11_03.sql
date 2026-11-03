-- The /artist-cleanup run of 2026-11-03, the half beside the brackets: a show's format fused onto its act, a status
-- prefix, two acts on one row, and placeholders, genre notes and nights billed as acts. Each source's parser no longer
-- makes them. A past night is never imported again, so the stored rows change here. Also M.O.E.'s wrong MusicBrainz match.
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
    -- The show's format, a status or an affiliation fused onto the act.
    ('sbp4-new-album-live-at-frannz',         'frannz:',             'sbp4',                    'SBP4',                    0),
    ('the-hamburg-blues-band-friends',        'maschinenhaus:',      'the-hamburg-blues-band',  'The Hamburg Blues Band',  0),
    ('david-haynes-and-friends',              'a_trane:',            'david-haynes',            'David Haynes',            0),
    ('unlimited-sonic-use-weihnachtskonzert', 'neue_zukunft:',       'unlimited-sonic-use',     'Unlimited Sonic Use',     0),
    ('wird-verlegt-deep-purple',              'uber_arena:',         'deep-purple',             'Deep Purple',             0),
    ('morimoto-wong-duo',                     'madame_claude:',      'morimoto-wong',           'Morimoto / Wong',         0),
    ('sir-oliver-skardy-of-pitura-freska',    'so36:',               'sir-oliver-skardy',       'Sir Oliver Skardy',       0),
    ('theatergeist-annegret-geist',           'kulturhaus_spandau:', 'theatergeist',            'TheaterGeist',            0),
    -- Two acts on one row, each in its place.
    ('caroline-cecilia-tallone-k-li',         'ausland:',            'caroline-cecilia-tallone', 'Caroline Cecilia Tallone', 0),
    ('caroline-cecilia-tallone-k-li',         'ausland:',            'k-li',                    'K Lì',                    1),
    ('ackern-76-chris-pitsiokos',             'richten25:',          'ackern-76',               'Ackern 76',               0),
    ('ackern-76-chris-pitsiokos',             'richten25:',          'chris-pitsiokos',         'Chris Pitsiokos',         1),
    ('gingerblack-sharhok-dini',              'golden_gate:',        'gingerblack',             'Gingerblack',             0),
    ('gingerblack-sharhok-dini',              'golden_gate:',        'sharhok-dini',            'Sharhok Dini',            1),
    ('djs-peter-louie-wray',                  'wild_at_heart:',      'dj-peter',                'DJ Peter',                0),
    ('djs-peter-louie-wray',                  'wild_at_heart:',      'louie-wray',              'Louie Wray',              1);

CREATE TEMPORARY TABLE replaced_link ON COMMIT DROP AS
SELECT ea.*, a.slug AS old_slug
FROM event_artist ea
JOIN artist a ON a.id = ea.artist_id
JOIN event e ON e.id = ea.event_id
WHERE EXISTS (SELECT 1 FROM replacement r WHERE r.old_slug = a.slug AND starts_with(e.source_id, r.source_prefix));

INSERT INTO artist (name, slug)
SELECT DISTINCT r.name, r.slug
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

-- Followers of a replaced row move onto its first act, unless the account follows it already. A follow names the
-- artist by id, with no foreign key (V195), so nothing else moves it.
CREATE TEMPORARY TABLE artist_move ON COMMIT DROP AS
SELECT DISTINCT o.id AS loser_id, a.id AS survivor_id
FROM replacement r
JOIN artist o ON o.slug = r.old_slug
JOIN artist a ON a.slug = r.slug
WHERE r.position = 0
  AND EXISTS (SELECT 1 FROM replaced_link l WHERE l.artist_id = o.id)
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

SELECT drop_artist_billed_only_by(old_slug, source_prefix) FROM (SELECT DISTINCT old_slug, source_prefix FROM replacement) r;

-- Not acts: placeholders, genre notes, a label's after-show line, and nights or parties billed as their own act.
SELECT drop_artist_billed_only_by('aftershow-party-mit-record-hop-einlass-20-00-uhr', 'roadrunner:');
SELECT drop_artist_billed_only_by('no-tears-for-the-creatures-of-the-night', 'schokoladen:');
SELECT drop_artist_billed_only_by('wave-alternative-vinyl-only', 'lux:');
SELECT drop_artist_billed_only_by('electropop-weird-tunes', 'lux:');
SELECT drop_artist_billed_only_by('dj-surprise', 'der_weisse_hase:');
SELECT drop_artist_billed_only_by('surprise', 'der_weisse_hase:');
SELECT drop_artist_billed_only_by('local-support', 'clash:');
SELECT drop_artist_billed_only_by('p-r-n-i-paranoid-club', 'lido:');
SELECT drop_artist_billed_only_by('arcana-a-night-of-flow', 'arcanoa:');
SELECT drop_artist_billed_only_by('butch-walk', 'so36:');
-- Their parser half is already on `main`: ufaFabrik's cancelled party (3bc1a176f) and LabSaal's dance workshop (e8881d47b).
SELECT drop_artist_billed_only_by('gretchens-christmas-party', 'ufa_fabrik:');
SELECT drop_artist_billed_only_by('gartn-eydn', 'labsaal:');

-- `m-o-e` is Eschschloraque's DJ, but the sweep matched it to a Japanese group and copied the group's facts and links.
-- The shape of V222's `what`: AMBIGUOUS queues it for /musicbrainz-review, and the Discogs sweep reads only NONE.
-- Guarded on the group's MBID, so a row already corrected is left alone.
UPDATE artist
SET musicbrainz_id = NULL,
    musicbrainz_match = 'AMBIGUOUS',
    musicbrainz_checked_at = now(),
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
WHERE slug = 'm-o-e'
  AND musicbrainz_id = '903a3bd5-4c34-43da-9217-acf8b72a1f04';
