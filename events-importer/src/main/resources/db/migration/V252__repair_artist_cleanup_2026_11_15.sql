-- Three rows the parsers minted before #5102, #5113 and #5124 fixed them. Bi Nuu billed a duo's two acts on one row,
-- Urania billed a speaker and her host on one row with `bei` between them, and Kantine am Berghain billed a visuals
-- credit as an act. A past night is never imported again, so the stored rows change here.
--
-- The shapes of V150 (a split keyed on the old slug and the source, the links after it moved down) and V244 (followers
-- moved, a drop keyed on slug and name). A row is a no-op where it is missing, because staging and production hold
-- different rows. Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

-- 1. Two acts on one row, each in its place.
CREATE TEMPORARY TABLE replacement (
    old_slug      TEXT NOT NULL,
    source_prefix TEXT NOT NULL,
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    position      INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO replacement (old_slug, source_prefix, slug, name, position) VALUES
    ('bedo-patron',                         'binuu:',  'bedo',              'Bedo',              0),
    ('bedo-patron',                         'binuu:',  'patron',            'Patron',            1),
    ('victoria-reichelt-bei-rabea-weihser', 'urania:', 'victoria-reichelt', 'Victoria Reichelt', 0),
    ('victoria-reichelt-bei-rabea-weihser', 'urania:', 'rabea-weihser',     'Rabea Weihser',     1);

CREATE TEMPORARY TABLE replaced_link ON COMMIT DROP AS
SELECT ea.*, a.slug AS old_slug, r.parts
FROM event_artist ea
JOIN artist a ON a.id = ea.artist_id
JOIN event e ON e.id = ea.event_id
JOIN (SELECT old_slug, source_prefix, count(*) AS parts FROM replacement GROUP BY old_slug, source_prefix) r
    ON r.old_slug = a.slug AND starts_with(e.source_id, r.source_prefix);

INSERT INTO artist (name, slug)
SELECT DISTINCT r.name, r.slug
FROM replacement r
WHERE EXISTS (SELECT 1 FROM replaced_link l WHERE l.old_slug = r.old_slug)
  AND NOT EXISTS (SELECT 1 FROM artist a WHERE a.slug = r.slug);

-- Make room: a link billed after a row with two acts moves down by one.
UPDATE event_artist ea
SET billing_order = ea.billing_order + l.parts - 1
FROM replaced_link l
WHERE ea.event_id = l.event_id
  AND ea.billing_order > l.billing_order;

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

-- 2. A row that is no act: Kantine's visuals credit.
CREATE TEMPORARY TABLE artist_drop (
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    source_prefix TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_drop (slug, name, source_prefix) VALUES
    ('4c-stefano-canavese', '4C & Stefano Canavese', 'kantine_am_berghain:');

SELECT drop_artist_billed_only_by(d.slug, d.source_prefix)
FROM artist_drop d
JOIN artist a ON a.slug = d.slug AND a.name = d.name;
