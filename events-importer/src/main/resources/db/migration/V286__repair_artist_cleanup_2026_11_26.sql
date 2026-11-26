-- The /artist-cleanup run of 2026-11-26. Soda's blurb lines, two Kühlspot night titles and Urania's moderator are no act,
-- and WIRD and Flightmode are rows no event bills. Schokoladen's `DJ:` label and Kesselhaus's split of a tribute band's
-- `&` are cut from three names, and three hand-made rows lose a Resident Advisor tag. One `JAS` row holds two acts.
-- NAME_CORRECTIONS folds three losers onto their survivors, and `Lobo (DE)` folds onto `Lobo`. A past night is never
-- imported again, so the stored rows change here.
--
-- V279's shapes: a replacement keyed on the old slug and the source, a merge keyed on the slug and the loser's name, and
-- a drop. A row is a no-op where it is missing or its name was changed by hand since, because staging and production
-- hold different rows. Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

-- 1. Renames, each act in its place.
CREATE TEMPORARY TABLE replacement (
    old_slug      TEXT NOT NULL,
    source_prefix TEXT NOT NULL,
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO replacement (old_slug, source_prefix, slug, name) VALUES
    ('dj-kingston-headroll', 'schokoladen:', 'kingston-headroll', 'Kingston Headroll'),
    ('queen-thunderbolt', 'maschinenhaus:', 'thunderbolt-lightning', 'Thunderbolt & Lightning'),
    ('lightning', 'maschinenhaus:', 'thunderbolt-lightning', 'Thunderbolt & Lightning'),
    ('two-dots-fr', 'manual:', 'two-dots', 'Two Dots'),
    ('tizian-de', 'manual:', 'tizian', 'TiZiAN'),
    ('ema-1', 'manual:', 'ema', 'EMA');

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

-- Two halves of one band land on one link, which keeps the first half's place in the bill.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT l.event_id, a.id, l.role, l.billing_order, l.stage, l.title_derived, l.set_start, l.set_end
FROM replaced_link l
JOIN replacement r ON r.old_slug = l.old_slug
JOIN artist a ON a.slug = r.slug
ORDER BY l.event_id, l.billing_order
ON CONFLICT (event_id, artist_id) DO NOTHING;

DELETE FROM event_artist ea
USING replaced_link l
WHERE ea.event_id = l.event_id
  AND ea.artist_id = l.artist_id;

-- Followers of a replaced row move onto the act, unless the account follows it already. A follow names the
-- artist by id, with no foreign key (V195), so nothing else moves it.
CREATE TEMPORARY TABLE replaced_move ON COMMIT DROP AS
SELECT DISTINCT o.id AS loser_id, a.id AS survivor_id
FROM replacement r
JOIN artist o ON o.slug = r.old_slug
JOIN artist a ON a.slug = r.slug
WHERE EXISTS (SELECT 1 FROM replaced_link l WHERE l.artist_id = o.id)
  AND NOT EXISTS (SELECT 1 FROM event_artist ea WHERE ea.artist_id = o.id);

UPDATE accounts.follow f
SET entity_id = mv.survivor_id
FROM replaced_move mv
WHERE f.kind = 'artist'
  AND f.entity_id = mv.loser_id
  AND NOT EXISTS (
      SELECT 1 FROM accounts.follow own
      WHERE own.account_id = f.account_id AND own.kind = 'artist' AND own.entity_id = mv.survivor_id
  );

DELETE FROM accounts.follow f
USING replaced_move mv
WHERE f.kind = 'artist'
  AND f.entity_id = mv.loser_id;

SELECT drop_artist_billed_only_by(old_slug, source_prefix) FROM (SELECT DISTINCT old_slug, source_prefix FROM replacement) r;

-- 2. Two acts on one row. Kesselhaus bills the German singer JAS, who keeps `jas`. Sensorium's TECHNO MITTWOCH of
-- 9 December bills a techno DJ, who gets a row of their own (V280's shape). No override sends a later billing there.
INSERT INTO artist (name, slug)
SELECT 'JAS', 'jas-2'
WHERE EXISTS (
    SELECT 1
    FROM event_artist ea
    JOIN artist a ON a.id = ea.artist_id
    JOIN event e ON e.id = ea.event_id
    WHERE a.slug = 'jas' AND a.name = 'JAS' AND e.source_id = 'manual:sensorium:2026-12-09-techno-mittwoch'
)
ON CONFLICT (slug) DO NOTHING;

UPDATE event_artist ea
SET artist_id = (SELECT id FROM artist WHERE slug = 'jas-2')
FROM artist a, event e
WHERE a.id = ea.artist_id
  AND e.id = ea.event_id
  AND a.slug = 'jas'
  AND a.name = 'JAS'
  AND e.source_id = 'manual:sensorium:2026-12-09-techno-mittwoch'
  AND EXISTS (SELECT 1 FROM artist WHERE slug = 'jas-2')
  AND NOT EXISTS (
      SELECT 1 FROM event_artist taken JOIN artist two ON two.id = taken.artist_id
      WHERE taken.event_id = ea.event_id AND two.slug = 'jas-2'
  );

-- 3. One act stored twice, which the operator confirmed.
CREATE TEMPORARY TABLE artist_merge (
    loser      TEXT NOT NULL,
    loser_name TEXT NOT NULL,
    survivor   TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_merge (loser, loser_name, survivor) VALUES
    ('jugo-urgens', 'Jugo Ürgens', 'jugo-urdens'),
    ('big-band-kameleon', 'Big Band Kameleon', 'bigband-kameleon'),
    ('satysfyre', 'Satysfyre', 'satys-fyre'),
    ('lobo-de', 'Lobo (DE)', 'lobo');

-- The loser's links, moved onto the survivor. A link keeps its role, billing order, stage and set times, and a night
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

-- A profile link the loser holds fills the survivor's empty one. A link the survivor holds stays.
UPDATE artist s
SET website_url = COALESCE(s.website_url, l.website_url),
    facebook_url = COALESCE(s.facebook_url, l.facebook_url),
    instagram_url = COALESCE(s.instagram_url, l.instagram_url),
    youtube_url = COALESCE(s.youtube_url, l.youtube_url),
    bandcamp_url = COALESCE(s.bandcamp_url, l.bandcamp_url),
    soundcloud_url = COALESCE(s.soundcloud_url, l.soundcloud_url),
    discogs_url = COALESCE(s.discogs_url, l.discogs_url),
    resident_advisor_url = COALESCE(s.resident_advisor_url, l.resident_advisor_url),
    spotify_url = COALESCE(s.spotify_url, l.spotify_url)
FROM artist_move mv
JOIN artist l ON l.id = mv.loser_id
WHERE s.id = mv.survivor_id;

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

-- The loser's former slugs move onto the survivor before the delete cascades them away, and the loser's slug becomes
-- one too (V159): the import resolves it to the survivor, and the admin API refuses to create it again.
UPDATE artist_slug_alias alias
SET artist_id = mv.survivor_id
FROM artist_move mv
WHERE alias.artist_id = mv.loser_id
  AND alias.slug <> mv.survivor_slug;

DELETE FROM artist l
USING artist_move mv
WHERE l.id = mv.loser_id;

INSERT INTO artist_slug_alias (slug, artist_id)
SELECT loser_slug, survivor_id
FROM artist_move
ON CONFLICT (slug) DO NOTHING;

-- 4. Rows that are no act. WIRD is Uber Arena's `Wird verschoben:` notice, kept only by a wrong MusicBrainz match.
CREATE TEMPORARY TABLE artist_drop (
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    source_prefix TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_drop (slug, name, source_prefix) VALUES
    ('15', '15 €', 'soda:'),
    ('22-uhr', '22 Uhr', 'soda:'),
    ('freier-eintritt-10-getrankebons-bis-0-uhr', 'Freier Eintritt & 10 € Getränkebons bis 0 Uhr', 'soda:'),
    ('eine-flasche-sekt-fur-dich-deine-freunde', 'Eine Flasche Sekt für dich & deine Freunde!', 'soda:'),
    ('ab-18-jahren-bitte-einen-gultigen-ausweis-mitbringen', 'Ab 18 Jahren – bitte einen gültigen Ausweis mitbringen.', 'soda:'),
    ('sunswept-sunday-crystal-ballroom-stories', 'Sunswept Sunday "Crystal Ballroom Stories"', 'kuehlspot:'),
    ('improvisation-and-the-reimagining-of-classical-traditions', 'Improvisation And The Reimagining Of Classical Traditions', 'kuehlspot:'),
    ('jochen-muller', 'Jochen Müller', 'urania:'),
    ('wird', 'WIRD', 'uber_arena:');

SELECT drop_artist_billed_only_by(d.slug, d.source_prefix)
FROM artist_drop d
JOIN artist a ON a.slug = d.slug AND a.name = d.name;

-- Flightmode is unbilled, and only a Discogs link keeps it. Renate and Sisyphos bill the DJ as `Flight Mode`.
DELETE FROM artist a
WHERE a.slug = 'flightmode'
  AND a.name = 'Flightmode'
  AND NOT EXISTS (SELECT 1 FROM event_artist ea WHERE ea.artist_id = a.id);
