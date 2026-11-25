-- Eight artists stored with the venue's live mark in the name (#5534): `Harald F. *Live`, `Erobique live*`. The import cuts
-- the mark now, but a past night is never imported again, so the stored rows change here.
--
-- Keyed on slug and name, so a row that is missing or was corrected by hand is left alone. Unqualified table names,
-- deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE starred (
    old_slug TEXT NOT NULL,
    old_name TEXT NOT NULL,
    slug     TEXT NOT NULL,
    name     TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO starred (old_slug, old_name, slug, name) VALUES
    ('ain-the-machine-live', 'Ain The Machine *live', 'ain-the-machine', 'Ain The Machine'),
    ('astronom-live', 'Astronom *LIVE', 'astronom', 'Astronom'),
    ('erobique-live', 'Erobique live*', 'erobique', 'Erobique'),
    ('harald-f-live', 'Harald F. *Live', 'harald-f', 'Harald F.'),
    ('hypogeo-live', 'Hypogeo *LIVE', 'hypogeo', 'Hypogeo'),
    ('ipotocaticac-live', 'Ipotocaticac *Live', 'ipotocaticac', 'Ipotocaticac'),
    ('tromo-live', 'Tromo *LIVE', 'tromo', 'Tromo'),
    ('yons-live', 'Yons *Live', 'yons', 'Yons');

-- 1. Where the clean row exists, the starred row merges into it, as V279 does.
CREATE TEMPORARY TABLE artist_move ON COMMIT DROP AS
SELECT l.id AS loser_id, l.slug AS loser_slug, s.id AS survivor_id, s.slug AS survivor_slug
FROM starred r
JOIN artist l ON l.slug = r.old_slug AND l.name = r.old_name
JOIN artist s ON s.slug = r.slug;

INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, mv.survivor_id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM event_artist ea
JOIN artist_move mv ON mv.loser_id = ea.artist_id
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- A follow names the artist by id, with no foreign key (V195), so nothing else moves it.
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

UPDATE artist_slug_alias alias
SET artist_id = mv.survivor_id
FROM artist_move mv
WHERE alias.artist_id = mv.loser_id
  AND alias.slug <> mv.survivor_slug;

DELETE FROM artist l
USING artist_move mv
WHERE l.id = mv.loser_id;

-- 2. Otherwise the row is renamed in place and keeps its id, links and follows. Its name change sends it back to
-- MusicBrainz and Discogs (V062).
CREATE TEMPORARY TABLE renamed ON COMMIT DROP AS
SELECT a.id, r.old_slug, r.slug, r.name
FROM starred r
JOIN artist a ON a.slug = r.old_slug AND a.name = r.old_name;

-- A former slug that becomes the row's own again is no alias.
DELETE FROM artist_slug_alias alias
USING renamed r
WHERE alias.slug = r.slug
  AND alias.artist_id = r.id;

UPDATE artist a
SET name = r.name,
    slug = r.slug
FROM renamed r
WHERE a.id = r.id
  AND NOT EXISTS (SELECT 1 FROM artist_slug_alias alias WHERE alias.slug = r.slug);

-- 3. Each old slug stays reachable as an alias (V159).
INSERT INTO artist_slug_alias (slug, artist_id)
SELECT loser_slug, survivor_id FROM artist_move
UNION ALL
SELECT r.old_slug, r.id FROM renamed r JOIN artist a ON a.id = r.id AND a.slug = r.slug
ON CONFLICT (slug) DO NOTHING;
