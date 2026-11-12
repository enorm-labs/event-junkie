-- The /artist-cleanup run of 2026-11-12. NAME_CORRECTIONS now folds seven losers' names onto their survivors, VOID and
-- 8MM's festival line-up drop a bracket after the act, and a line-up entry with a web address or a floor host's credit is
-- no act. ÆDEN's `mølly (on molly)` was minted again before V223 reached production, and Kantine billed two solo artists
-- on one row. A past night is never imported again, so the stored rows change here.
--
-- The shapes of V224 (a replacement keyed on the old slug and the source), V225 (a merge keyed on slug and on the
-- loser's name) and V229 (a drop). A row is a no-op where it is missing or its name was changed by hand since, because
-- staging and production hold different rows. Unqualified table names, deliberately: Flyway sets `search_path` from
-- `spring.flyway.schemas` (ADR-004).

-- 1. Renames and a split, each act in its place.
CREATE TEMPORARY TABLE replacement (
    old_slug      TEXT NOT NULL,
    source_prefix TEXT NOT NULL,
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    position      INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO replacement (old_slug, source_prefix, slug, name, position) VALUES
    ('primal-rhapsody-dahraxt',                  'void_club:',           'primal-rhapsody',      'Primal Rhapsody',      0),
    ('wedding-cpn',                              'eight_mm:',            'wedding',              'Wedding',              0),
    ('molly-on-molly',                           'aeden:',               'molly',                'mølly',                0),
    ('radwan-ghazi-moumneh-frederic-d-oberland', 'kantine_am_berghain:', 'radwan-ghazi-moumneh', 'Radwan Ghazi Moumneh', 0),
    ('radwan-ghazi-moumneh-frederic-d-oberland', 'kantine_am_berghain:', 'frederic-d-oberland',  'Frédéric D. Oberland', 1);

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
CREATE TEMPORARY TABLE replaced_move ON COMMIT DROP AS
SELECT DISTINCT o.id AS loser_id, a.id AS survivor_id
FROM replacement r
JOIN artist o ON o.slug = r.old_slug
JOIN artist a ON a.slug = r.slug
WHERE r.position = 0
  AND EXISTS (SELECT 1 FROM replaced_link l WHERE l.artist_id = o.id)
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

-- 2. One act stored twice, which the operator confirmed.
CREATE TEMPORARY TABLE artist_merge (
    loser      TEXT NOT NULL,
    loser_name TEXT NOT NULL,
    survivor   TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_merge (loser, loser_name, survivor) VALUES
    ('dj-defjam',           'DJ DefJam',           'defjam'),
    ('dj-bat',              'DJ Bat',              'bat'),
    ('dj-selekta-mik',      'DJ Selekta Mik',      'selekta-mik'),
    ('dj-angelic-sintesis', 'DJ Angelic Sintesis', 'angelic-sintesis'),
    ('dj-hauke-pauke',      'DJ Hauke_Pauke',      'dj-haukepauke'),
    ('dj-high-score',       'DJ High Score',       'high-score'),
    ('rabbigreen',          'RabbiGreen',          'rabbi-green');

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

-- 3. Two rows that are no act: Renate's workshop sign-up line, and Ritter Butzke's floor-host credit.
CREATE TEMPORARY TABLE artist_drop (
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    source_prefix TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_drop (slug, name, source_prefix) VALUES
    ('sign-up-here-https-forms-gle-yrdr1dz7cifgr7jc9', 'Sign Up Here: https://forms.gle/yRDr1DZ7ciFGr7Jc9', 'renate:'),
    ('floor-hosting-by-sachsentrance',                 'Floor Hosting By Sachsentrance',                   'ritter_butzke:');

SELECT drop_artist_billed_only_by(d.slug, d.source_prefix)
FROM artist_drop d
JOIN artist a ON a.slug = d.slug AND a.name = d.name;
