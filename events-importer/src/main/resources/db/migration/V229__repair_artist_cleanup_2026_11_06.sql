-- The /artist-cleanup run of 2026-11-06. NAME_CORRECTIONS now folds two losers' names onto their survivors, Urban Spree
-- bills the act of a `“…”-Releaseshow`, Monarch's numbered session bills nobody, and a name ending on `w/` is no act.
-- SOURCE_ARTIST_SLUG_OVERRIDES gives OHM's opera act `V.` the slug `v-2`, so the dark-wave act `V` keeps its own row. A
-- past night is never imported again, so the stored rows change here.
--
-- The shape of V225's merge, keyed on slug and on the old name. A row is a no-op where it is missing or its name was
-- changed by hand since, because staging and production hold different rows. Unqualified table names, deliberately: Flyway
-- sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE artist_merge (
    loser         TEXT NOT NULL,
    loser_name    TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_merge (loser, loser_name, survivor, survivor_name) VALUES
    ('dj-louie-wray',                   'DJ Louie Wray',                     'louie-wray',  'Louie Wray'),
    ('panamamama',                      'Panamamama',                        'panama-mama', 'Panama Mama'),
    ('friberty-friberty-i-releaseshow', 'Friberty “Friberty I”-Releaseshow', 'friberty',    'Friberty'),
    ('v-opera-live',                    'V. (Opera live)',                   'v-2',         'V.');

-- 1. A rename onto a clean slug is a merge whose survivor may not exist yet. A merge pair's missing survivor is not created.
INSERT INTO artist (name, slug)
SELECT m.survivor_name, m.survivor
FROM artist_merge m
JOIN artist l ON l.slug = m.loser AND l.name = m.loser_name
WHERE m.survivor IN ('friberty', 'v-2')
ON CONFLICT (slug) DO NOTHING;

-- 2. The loser's links, moved onto the survivor. A link keeps its role, billing order, stage and set times, and a night
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

-- 3. Followers, moved onto the survivor unless the account follows it already. A follow names the artist by id, with no
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

-- 4. The loser's former slugs move onto the survivor before the delete cascades them away, and the loser's slug becomes
-- one too (V159): the import resolves it to the survivor, and the admin API refuses to create it again.
UPDATE artist_slug_alias alias
SET artist_id = mv.survivor_id
FROM artist_move mv
WHERE alias.artist_id = mv.loser_id
  AND alias.slug <> mv.survivor_slug;

-- 5. The losers, gone with their links.
DELETE FROM artist l
USING artist_move mv
WHERE l.id = mv.loser_id;

INSERT INTO artist_slug_alias (slug, artist_id)
SELECT loser_slug, survivor_id
FROM artist_move
ON CONFLICT (slug) DO NOTHING;

-- 6. Two rows that are no act: Monarch's `LEAKED SESSION 04 (KONZERT)` lists no act, and Renate's `Whacking Activation w/`
-- is an activation whose guest was cut off.
CREATE TEMPORARY TABLE artist_drop (
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    source_prefix TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_drop (slug, name, source_prefix) VALUES
    ('leaked-session-04',     'Leaked Session 04',      'monarch:'),
    ('whacking-activation-w', 'Whacking Activation w/', 'renate:');

SELECT drop_artist_billed_only_by(d.slug, d.source_prefix)
FROM artist_drop d
JOIN artist a ON a.slug = d.slug AND a.name = d.name;
