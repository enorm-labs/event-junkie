-- Ten `X b2b Y` rows on past nights, which the parsers split at import since #1759, #1844 and #2966 (#3024). A past
-- night is never imported again, so its joined act is split here. Each half takes the joined row's place, keeping its
-- role, stage and set times; the second half bills right after the first and every later act moves down one. A half
-- without a row yet is created, then the joined row goes with drop_artist_billed_only_by. `Michael Lane b2b` has
-- only the one half. A half created here starts UNCHECKED by the column default, so the MusicBrainz pass reads it.
--
-- V150's shape, keyed on slug and on source, so a database without the row, or a same-slug row another venue bills, is
-- left alone. Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE replacement (
    old_slug      TEXT NOT NULL,
    source_prefix TEXT NOT NULL,
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    position      INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO replacement (old_slug, source_prefix, slug, name, position) VALUES
    ('woody92-b2b-floid',               'berghain:',    'woody92',         'Woody92',         0),
    ('woody92-b2b-floid',               'berghain:',    'floid',           'Floid',           1),
    ('sibil-b2b-tau-car',               'berghain:',    'sibil',           'Sibil',           0),
    ('sibil-b2b-tau-car',               'berghain:',    'tau-car',         'Tau Car',         1),
    ('dash-b2b-lennart',                'berghain:',    'dash',            'Dash',            0),
    ('dash-b2b-lennart',                'berghain:',    'lennart',         'Lennart',         1),
    ('franz-scala-b2b-giulia-gutterer', 'berghain:',    'franz-scala',     'Franz Scala',     0),
    ('franz-scala-b2b-giulia-gutterer', 'berghain:',    'giulia-gutterer', 'Giulia Gutterer', 1),
    ('emira-b2b-ayham',                 'aeden:',       'emira',           'Emira',           0),
    ('emira-b2b-ayham',                 'aeden:',       'ayham',           'Ayham',           1),
    ('vaccaro-b2b-the-camel',           'aeden:',       'vaccaro',         'Vaccaro',         0),
    ('vaccaro-b2b-the-camel',           'aeden:',       'the-camel',       'The Camel',       1),
    ('allynx-b2b-sean-steinfeger',      'gretchen:',    'allynx',          'Allynx',          0),
    ('allynx-b2b-sean-steinfeger',      'gretchen:',    'sean-steinfeger', 'Sean Steinfeger', 1),
    ('michael-lane-b2b',                'gartn:',       'michael-lane',    'Michael Lane',    0),
    ('artness-b2b-buyooo',              'matrix:',      'artness',         'Artness',         0),
    ('artness-b2b-buyooo',              'matrix:',      'buyooo',          'Buyooo',          1),
    ('anate-b2b-shira-kela',            'golden_gate:', 'anate',           'Anaté',           0),
    ('anate-b2b-shira-kela',            'golden_gate:', 'shira-kela',      'Shira Kela',      1);

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

-- 2. Make room: a link moves down by one for each two-act row billed before it on its event. One night bills two
--    joined rows (Berghain's Italorama Bar), so the shift is a sum, not a single step.
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
