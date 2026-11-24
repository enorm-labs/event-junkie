-- The /artist-cleanup run of 2026-11-24. Kater's lines carried set times on 20 and 21 November, and the scraper read each
-- time as part of the act's name. 808 and Tresor drop a bracket after the act, and a birthday note, a floor takeover and
-- the migas audience session are no act. Three Supamolly rows keep what its scraper strips now, and NAME_CORRECTIONS folds
-- nine losers' names onto their survivors. A past night is never imported again, so the stored rows change here.
--
-- V244's shapes: a replacement keyed on the old slug and the source, a merge keyed on the slug and the loser's name, and
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
    ('00-00-gti', 'kater:', 'gti', 'Gti'),
    ('00-00-horst-haller', 'kater:', 'horst-haller', 'Horst Haller'),
    ('00-30-schmusi', 'kater:', 'schmusi', 'Schmusi'),
    ('01-00-manque', 'kater:', 'manque', 'Manque'),
    ('01-00-robin-flux', 'kater:', 'robin-flux', 'Robin Flux'),
    ('01-30-aio', 'kater:', 'aio', 'Aio'),
    ('02-00-uberhaupt', 'kater:', 'uberhaupt', 'Überhaupt'),
    ('02-30-per-hammar', 'kater:', 'per-hammar', 'Per Hammar'),
    ('03-00-elaa', 'kater:', 'elaa', 'Élaa'),
    ('03-00-vero-standke', 'kater:', 'vero-standke', 'Vero Standke'),
    ('03-30-kotelett', 'kater:', 'kotelett', 'Kotelett'),
    ('03-30-taberia', 'kater:', 'taberia', 'Taberia'),
    ('04-00-nina-hepburn', 'kater:', 'nina-hepburn', 'Nina Hepburn'),
    ('04-00-phonk-d', 'kater:', 'phonk-d', 'Phonk D'),
    ('05-00-hanna-baertig', 'kater:', 'hanna-baertig', 'Hanna Baertig'),
    ('05-00-nikolaus-friedlich', 'kater:', 'nikolaus-friedlich', 'Nikolaus Friedlich'),
    ('05-00-ventura', 'kater:', 'ventura', 'Ventura'),
    ('05-30-bonnie-spacey', 'kater:', 'bonnie-spacey', 'Bonnie Spacey'),
    ('06-00-hovr', 'kater:', 'hovr', 'Hovr'),
    ('07-00-alison-swing', 'kater:', 'alison-swing', 'Alison Swing'),
    ('08-00-just-emma', 'kater:', 'just-emma', 'Just Emma'),
    ('22-00-r3nata', 'kater:', 'r3nata', 'R3NATA'),
    ('22-00-snow', 'kater:', 'snow', 'Snow'),
    ('23-00-reza-jalilian', 'kater:', 'reza-jalilian', 'Reza Jalilian'),
    ('23-30-konfusia', 'kater:', 'konfusia', 'Konfusia'),
    ('amber-rose-host', 'nachtklub_808:', 'amber-rose', 'Amber Rose'),
    ('gelbes-pep-aus-dusseldorf', 'supamolly:', 'gelbes-pep', 'Gelbes Pep'),
    ('aftershow-mit-dr-kimble', 'supamolly:', 'dr-kimble', 'Dr. Kimble'),
    ('aftershowparty-mit-ilia-eric', 'supamolly:', 'ilia-eric', 'Ilia & Eric');

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
SELECT l.event_id, a.id, l.role, l.billing_order, l.stage, l.title_derived, l.set_start, l.set_end
FROM replaced_link l
JOIN replacement r ON r.old_slug = l.old_slug
JOIN artist a ON a.slug = r.slug
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

-- 2. One act stored twice, which the operator confirmed.
CREATE TEMPORARY TABLE artist_merge (
    loser      TEXT NOT NULL,
    loser_name TEXT NOT NULL,
    survivor   TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_merge (loser, loser_name, survivor) VALUES
    ('dj-hey-my-commander', 'DJ Hey, my Commander', 'hey-my-commander'),
    ('dj-stan', 'DJ Stan', 'stan'),
    ('dj-mig', 'DJ Mig', 'mig'),
    ('dj-isamel-nagera', 'DJ Isamel Nagera', 'isamel-nagera'),
    ('dj-onef', 'DJ Onef', 'dj-one-f'),
    ('dj-barox', 'DJ Barox', 'barox'),
    ('young-oldmann', 'young oldmann', 'youngoldmann'),
    ('ellie-phunk', 'Ellie Phunk', 'elliephunk'),
    ('dj-volvox', 'Dj Volvox', 'volvox');

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

-- 3. Rows that are no act, and a set note beside the act the same night bills.
CREATE TEMPORARY TABLE artist_drop (
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    source_prefix TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_drop (slug, name, source_prefix) VALUES
    ('klausis-bday-bash-fyt-bunker-floor', 'Klausis BDay Bash (FYT Bunker Floor)', 'm_bia:'),
    ('listening-club', 'Listening Club', 'migas:'),
    ('01-00-special-guest', '01:00 Special Guest', 'kater:'),
    ('huettenbar-takeover', 'Huettenbar Takeover', 'ritter_butzke:'),
    ('appleblim-grime-set', 'Appleblim (Grime Set)', 'tresor:');

SELECT drop_artist_billed_only_by(d.slug, d.source_prefix)
FROM artist_drop d
JOIN artist a ON a.slug = d.slug AND a.name = d.name;
