-- Thirteen acts stored twice, which /artist-cleanup found and the owner confirmed: one DJ billed with and without the
-- prefix (`DJ Buttchin` beside `Buttchin`, both at Alte Kantine), or one act spaced two ways by two venues (`TraumaMia`
-- beside `Trauma Mia`). NAME_CORRECTIONS now folds each spelling onto one. A past event is never imported again, so the
-- loser's links move here.
--
-- Keyed on slug and a no-op where a row is absent. A link keeps its role, billing order, stage and set times, and an
-- event that already bills the survivor keeps its own link. Unqualified table names, deliberately: Flyway sets
-- `search_path` from `spring.flyway.schemas` before running this (ADR-004).

CREATE TEMPORARY TABLE artist_merge (
    loser         TEXT NOT NULL,
    survivor      TEXT NOT NULL,
    survivor_name TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO artist_merge (loser, survivor, survivor_name) VALUES
    ('dj-ben-washington',  'ben-washington', 'Ben Washington'),
    ('dj-buttchin',        'buttchin',       'Buttchin'),
    ('dj-danny-d',         'danny-d',        'Danny D'),
    ('dj-sterni',          'sterni',         'Sterni'),
    ('dj-troy-mytea',      'troy-mytea',     'Troy Mytea'),
    ('dj-allynx',          'allynx',         'Allynx'),
    ('dj-eggs-delicious',  'eggs-delicious', 'Eggs Delicious'),
    ('dj-lobotomy',        'lobotomy',       'Lobotomy'),
    ('bluesky',            'blue-sky',       'Blue Sky'),
    ('traumamia',          'trauma-mia',     'Trauma Mia'),
    ('limboboys',          'limbo-boys',     'Limbo Boys'),
    ('uppercuts',          'the-uppercuts',  'The Uppercuts'),
    ('dark-tenor',         'the-dark-tenor', 'The Dark Tenor');

-- 1. A missing survivor is created, only when its loser exists.
INSERT INTO artist (name, slug)
SELECT m.survivor_name, m.survivor
FROM artist_merge m
WHERE EXISTS (SELECT 1 FROM artist l WHERE l.slug = m.loser)
  AND NOT EXISTS (SELECT 1 FROM artist s WHERE s.slug = m.survivor);

-- 2. The display name on a survivor the venue spelt differently (`sterni`).
UPDATE artist a
SET name = m.survivor_name
FROM artist_merge m
WHERE a.slug = m.survivor
  AND a.name <> m.survivor_name;

-- 3. The loser's links, moved onto the survivor.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, s.id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM event_artist ea
JOIN artist l ON l.id = ea.artist_id
JOIN artist_merge m ON m.loser = l.slug
JOIN artist s ON s.slug = m.survivor
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 4. The losers, gone with their links.
DELETE FROM artist l
USING artist_merge m
WHERE l.slug = m.loser
  AND EXISTS (SELECT 1 FROM artist s WHERE s.slug = m.survivor);
