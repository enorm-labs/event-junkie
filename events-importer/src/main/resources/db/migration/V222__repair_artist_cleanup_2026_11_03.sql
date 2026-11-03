-- The /artist-cleanup run of 2026-11-03, the part #4440 waits for: it copies a stored name onto a shouted title, so the
-- name has to be right first. The de-shouter now keeps a one-word name without a vowel in capitals and de-shouts each
-- part a `*` joins, NAME_CORRECTIONS holds the rest, and Tempodrom and Festsaal no longer bill a show as an act. A row
-- keeps the name whichever import minted it, and a past night is never imported again, so the stored rows change here.
--
-- Keyed on slug, and on the old name or the source, so a database without the row, a name changed by hand since, or a
-- same-slug row another venue bills is left alone. Unqualified table names, deliberately: Flyway sets `search_path` from
-- `spring.flyway.schemas` (ADR-004).

-- 1. Display names; the slug stays, so no link moves. Pinned as in V210, so enrichment does not write a letter case back.
CREATE TEMPORARY TABLE display_name (
    slug     TEXT NOT NULL,
    old_name TEXT NOT NULL,
    name     TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO display_name (slug, old_name, name) VALUES
    -- One word without a vowel. The venue's prose writes the last seven in capitals.
    ('cctv',              'Cctv',               'CCTV'),
    ('sml',               'Sml',                'SML'),
    ('tzk',               'Tzk',                'TZK'),
    ('btmk',              'Btmk',               'BTMK'),
    ('tmbm',              'Tmbm',               'TMBM'),
    ('drs',               'Drs',                'DRS'),
    ('lsb',               'Lsb',                'LSB'),
    ('mksm',              'Mksm',               'MKSM'),
    ('spk',               'Spk',                'SPK'),
    ('tss',               'Tss',                'TSS'),
    ('vrtl',              'Vrtl',               'VRTL'),
    ('mzr',               'Mzr',                'MZR'),
    -- One word with a vowel, which the act writes in capitals.
    ('muna',              'Muna',               'MUNA'),
    ('naft',              'Naft',               'NAFT'),
    ('jas',               'Jas',                'JAS'),
    ('rian',              'Rian',               'RIAN'),
    ('rory',              'Røry',               'RØRY'),
    ('tara',              'Tära',               'TÄRA'),
    ('sind',              'Sind',               'SIND'),
    ('rapk',              'Rapk',               'RAPK'),
    ('unnv',              'Unnv',               'UNNV'),
    ('jud',               'Jud',                'JUD'),
    ('cave',              'Cave',               'CAVE'),
    ('emf',               'Emf',                'EMF'),
    ('what',              'What',               'WHAT'),
    -- Spellings the de-shouter could not reach.
    ('biga-ranx',         'Biga*ranx',          'Biga*Ranx'),
    ('toktok-vs-soffy-o', 'TOKTOK VS. SOFFY O', 'Toktok vs. Soffy O.');

UPDATE artist a
SET name = d.name,
    name_pinned = true
FROM display_name d
WHERE a.slug = d.slug
  AND a.name = d.old_name;

-- 2. `what` is Wild at Heart's German punk band, but the sweep matched it to a drone duo and copied the duo's facts and
-- links. The shape of V167's `gomez`: AMBIGUOUS queues it for /musicbrainz-review, and the Discogs sweep reads only NONE.
-- `musicbrainz_checked_at` takes the rename's `now()`, so the lookup sweep does not ask again (V062). Guarded on the
-- duo's MBID, so a row already corrected is left alone.
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
WHERE slug = 'what'
  AND musicbrainz_id = '22ca4615-8d72-41dd-9c60-3e9628f0002a';

-- 3. A new slug: the V150 shape. The act's row is created, takes the old row's links, and the old row goes.
CREATE TEMPORARY TABLE replacement (
    old_slug      TEXT NOT NULL,
    source_prefix TEXT NOT NULL,
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    position      INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO replacement (old_slug, source_prefix, slug, name, position) VALUES
    ('mr-hurley-und-die-pulveraffen', 'huxleys:',      'mr-hurley-die-pulveraffen', 'Mr. Hurley & Die Pulveraffen', 0),
    ('d-b-s',                         'neue_zukunft:', 'dxbxsx',                    'DxBxSx',                       0);

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

-- 4. One act stored twice, which the operator confirmed: the shape of V181, keyed on the loser's name too. The loser's
-- links move onto the survivor, and so do its followers, unless the account follows the survivor already.
CREATE TEMPORARY TABLE artist_merge (
    loser         TEXT NOT NULL,
    loser_name    TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_merge (loser, loser_name, survivor, survivor_name) VALUES
    ('dontom',                     'DonTom',                     'don-tom',                        'Don Tom'),
    ('lady-bird',                  'Lady Bird',                  'ladybird',                       'LadyBird'),
    ('dj-m-o-e',                   'DJ M.o.E',                   'm-o-e',                          'M.O.E.'),
    ('django-reinhardt-orchestra', 'Django Reinhardt Orchestra', 'the-django-reinhardt-orchestra', 'The Django Reinhardt Orchestra'),
    ('rave-d-amor',                'Rave d Amor',                'rave-damor',                     'Rave d’Amor');

INSERT INTO artist (name, slug)
SELECT m.survivor_name, m.survivor
FROM artist_merge m
WHERE EXISTS (SELECT 1 FROM artist l WHERE l.slug = m.loser AND l.name = m.loser_name)
  AND NOT EXISTS (SELECT 1 FROM artist s WHERE s.slug = m.survivor);

INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, s.id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM event_artist ea
JOIN artist l ON l.id = ea.artist_id
JOIN artist_merge m ON m.loser = l.slug AND m.loser_name = l.name
JOIN artist s ON s.slug = m.survivor
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 5. Followers of a row that goes, moved onto the row that takes its place. A follow names the artist by id, with no
-- foreign key (V195), so nothing else moves it.
CREATE TEMPORARY TABLE artist_move ON COMMIT DROP AS
SELECT l.id AS loser_id, s.id AS survivor_id
FROM artist_merge m
JOIN artist l ON l.slug = m.loser AND l.name = m.loser_name
JOIN artist s ON s.slug = m.survivor
UNION
SELECT DISTINCT o.id, a.id
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

-- 6. The rows that go: the merge losers, the replaced rows, and three shows billed as acts. Tempodrom's `Beat It!` is a
-- Michael Jackson show, Festsaal's `Anna` one of Rosenstolz's songs, and Gärten der Welt's `Die Mega Ü30 Party` a party.
DELETE FROM artist l
USING artist_merge m
WHERE l.slug = m.loser
  AND l.name = m.loser_name
  AND EXISTS (SELECT 1 FROM artist s WHERE s.slug = m.survivor);

SELECT drop_artist_billed_only_by(old_slug, source_prefix) FROM replacement;
SELECT drop_artist_billed_only_by('beat-it', 'tempodrom:');
SELECT drop_artist_billed_only_by('anna', 'festsaal:');
SELECT drop_artist_billed_only_by('die-mega-u30-party', 'gaerten_der_welt:');
