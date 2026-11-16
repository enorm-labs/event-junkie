-- The first /genre-cleanup sweep of production, 2026-11-16. `/api/genres` lists every `genre_tag` row, and a past event
-- is never imported again, so a tag the normalizer no longer writes stays public while one old event links it.
--
-- The dropped tags name a format, an event type, an audience or a night, not a style. Most are `NON_GENRE_TOKENS` keys
-- already, or hold one as a word; four became keys in the same change. The rest come from one past night each, and a
-- key for them would also veto a real style (`Future Beats`, `Neue Musik`). `event_genre_tag` follows the tag by `ON DELETE CASCADE`.
--
-- The folded tags are spellings `GENRE_SYNONYMS` already maps onto another tag. The links move, as in V242, and a
-- database with no canonical tag renames the first variant instead.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE genre_tag_fold (old_slug text, new_slug text, new_name text, new_family text) ON COMMIT DROP;
INSERT INTO genre_tag_fold
VALUES ('top-40', 'top40', 'Top40', 'charts'),
       ('lo-fi', 'lofi', 'Lofi', 'electronic'),
       ('rocknroll', 'rock-n-roll', 'Rock''n''Roll', 'rock'),
       ('r-n-r', 'rock-n-roll', 'Rock''n''Roll', 'rock'),
       ('dream-pop', 'dreampop', 'Dreampop', 'pop'),
       ('electro-pop', 'electropop', 'Electropop', 'pop'),
       ('street-punk', 'streetpunk', 'Streetpunk', 'punk'),
       ('raggaeton', 'latin', 'Latin', 'latin-world');

-- One rename per canonical tag at most: the first old tag takes the name, and the others fold onto it below.
UPDATE genre_tag g
SET name       = f.new_name,
    slug       = f.new_slug,
    family     = f.new_family,
    updated_at = now()
FROM (SELECT DISTINCT ON (new_slug) old_slug, new_slug, new_name, new_family
      FROM genre_tag_fold
               JOIN genre_tag held ON held.slug = old_slug
      ORDER BY new_slug, old_slug) f
WHERE g.slug = f.old_slug
  AND NOT EXISTS (SELECT 1 FROM genre_tag held WHERE held.slug = f.new_slug);

INSERT INTO event_genre_tag (event_id, genre_tag_id, origin)
SELECT egt.event_id, target.id, egt.origin
FROM event_genre_tag egt
         JOIN genre_tag old ON old.id = egt.genre_tag_id
         JOIN genre_tag_fold f ON f.old_slug = old.slug
         JOIN genre_tag target ON target.slug = f.new_slug
ON CONFLICT (event_id, genre_tag_id) DO NOTHING;

DELETE
FROM genre_tag
WHERE slug IN (SELECT old_slug FROM genre_tag_fold)
   OR slug IN (
    -- Already vetoed on main, as a key or by a key word
    'art-workshop', 'best-of-revue', 'burlesque', 'dj-sets', 'djs', 'film-screening', 'gesprach',
    'improvisationstheater', 'kabarett-theater', 'kneipenquiz', 'leseshow', 'lesungen', 'live-band', 'market',
    'modernes-theater', 'musical', 'panel', 'puppenshow', 'queer', 'talk', 'voll-playback-theater', 'movement',
    'beton-arme', 'buchpremieren', 'community-event', 'core', 'groovy', 'kindertheater', 'musikcomedy', 'neuer-zirkus',
    'performances', 'ping-pong', 'poledance', 'stand-up', 'tattoo', 'video', 'workshops', 'queer-icons', 'groovy-beats',
    -- New NON_GENRE_TOKENS keys
    'creative', 'social', 'filth', 'whacking',
    -- One past night, no rule
    '50-s-heartbreakers', 'beats', 'black', 'musik', 'hexcasterband', 'mettalx'
    );

-- The tag kept the spelling of the venue that minted it. An import does not rename an existing row, so the canonical
-- name is set here, keyed on the old name so a hand correction stays.
UPDATE genre_tag
SET name = 'Rock''n''Roll'
WHERE slug = 'rock-n-roll'
  AND name = 'Rock''n Roll';
