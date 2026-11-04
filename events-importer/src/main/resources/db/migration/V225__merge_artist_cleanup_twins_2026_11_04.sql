-- The /artist-cleanup run of 2026-11-03, the merges the operator decided: one DJ stored with and without `DJ `, and one act
-- spelled with and without a space. NAME_CORRECTIONS now folds each loser's name onto its survivor, so the next import
-- links the survivor. A past night is never imported again, so the stored links move here.
--
-- The shape of V222's merge, keyed on slug and on the loser's name. A pair is a no-op where either row is missing or the
-- loser's name was changed by hand since, because staging and production hold different rows. Unlike V222, a missing
-- survivor is not created. Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- (ADR-004).

CREATE TEMPORARY TABLE artist_merge (
    loser         TEXT NOT NULL,
    loser_name    TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_merge (loser, loser_name, survivor, survivor_name) VALUES
    ('dj-oberst-panizza', 'DJ Oberst Panizza', 'oberst-panizza', 'Oberst Panizza'),
    ('dj-jonas-poppe',    'DJ Jonas Poppe',    'jonas-poppe',    'Jonas Poppe'),
    ('dj-gina-d-orio',    'DJ gina d''orio',   'gina-d-orio',    'Gina d''Orio'),
    ('dj-lars-vegas',     'DJ Lars Vegas',     'lars-vegas',     'Lars Vegas'),
    ('dj-manu-beatz',     'DJ Manu Beatz',     'manu-beatz',     'Manu Beatz'),
    ('dj-miss-parker',    'DJ Miss Parker',    'miss-parker',    'Miss Parker'),
    ('dj-testbild',       'DJ Testbild',       'testbild',       'Testbild'),
    ('muffcase',          'Muffcase',          'muff-case',      'Muff Case'),
    ('lushlab',           'Lushlab',           'lush-lab',       'Lush Lab'),
    ('traum-trax',        'Traum Trax',        'traumtrax',      'TraumTrax'),
    ('rifraf',            'RifRaf',            'rif-raf',        'Rif Raf');

-- 1. The loser's links, moved onto the survivor. A link keeps its role, billing order, stage and set times, and a night
-- that bills both rows keeps the survivor's own link.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, s.id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM event_artist ea
JOIN artist l ON l.id = ea.artist_id
JOIN artist_merge m ON m.loser = l.slug AND m.loser_name = l.name
JOIN artist s ON s.slug = m.survivor
ON CONFLICT (event_id, artist_id) DO NOTHING;

CREATE TEMPORARY TABLE artist_move ON COMMIT DROP AS
SELECT l.id AS loser_id, l.slug AS loser_slug, s.id AS survivor_id, s.slug AS survivor_slug
FROM artist_merge m
JOIN artist l ON l.slug = m.loser AND l.name = m.loser_name
JOIN artist s ON s.slug = m.survivor;

-- 2. Followers, moved onto the survivor unless the account follows it already. A follow names the artist by id, with no
-- foreign key (V195), so nothing else moves it.
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

-- 3. The loser's former slugs move onto the survivor before the delete cascades them away, and the loser's slug becomes
-- one too (V159): the import resolves it to the survivor, and the admin API refuses to create it again.
UPDATE artist_slug_alias alias
SET artist_id = mv.survivor_id
FROM artist_move mv
WHERE alias.artist_id = mv.loser_id
  AND alias.slug <> mv.survivor_slug;

-- 4. The losers, gone with their links.
DELETE FROM artist l
USING artist_move mv
WHERE l.id = mv.loser_id;

INSERT INTO artist_slug_alias (slug, artist_id)
SELECT loser_slug, survivor_id
FROM artist_move
ON CONFLICT (slug) DO NOTHING;

-- 5. `MANU BEATZ` was a hand-entered row in capitals. Pinned as in V210, so enrichment does not write a letter case back.
UPDATE artist
SET name = 'Manu Beatz',
    name_pinned = true
WHERE slug = 'manu-beatz'
  AND name = 'MANU BEATZ';

-- 6. `rif-raf` is the Berlin DJ at Club der Visionäre, but the sweep matched it to a Canadian group and copied the group's
-- facts. The shape of V222's `what`: AMBIGUOUS queues it for /musicbrainz-review, and `musicbrainz_checked_at` takes
-- `now()`, so the lookup sweep does not ask again (V062). Guarded on the group's MBID, so a row already corrected is left
-- alone.
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
WHERE slug = 'rif-raf'
  AND musicbrainz_id = '9f220e7d-28d5-41db-8376-947964765fc5';
