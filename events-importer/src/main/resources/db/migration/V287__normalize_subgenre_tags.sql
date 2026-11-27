-- Subgenre tags get one spelling, and a venue's one-off compound folds onto the subgenre it names (#5592). The spelling is
-- title case with a hyphen: `Post-Rock`, `Dream-Pop`, `Hard-Rock`. A real subgenre keeps its own tag, and none folds onto its
-- family's broad tag, because an event page shows every tag by name.
--
-- An import does not rename an existing row, so a tag whose slug stays takes the new name here, keyed on the old name so
-- a hand correction stays. A tag whose slug changes moves its links, as in V257: a database with no survivor renames the
-- first variant instead, and the variants then fold onto it. `event_genre_tag` follows a deleted tag by `ON DELETE CASCADE`.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE genre_tag_respell (old_name text, slug text, new_name text) ON COMMIT DROP;
INSERT INTO genre_tag_respell
VALUES ('Post-rock', 'post-rock', 'Post-Rock'),
       ('Post Hardcore', 'post-hardcore', 'Post-Hardcore'),
       ('Post Grunge', 'post-grunge', 'Post-Grunge'),
       ('Glam-rock', 'glam-rock', 'Glam-Rock'),
       ('Math-rock', 'math-rock', 'Math-Rock'),
       ('Noise-rock', 'noise-rock', 'Noise-Rock'),
       ('Desert-rock', 'desert-rock', 'Desert-Rock'),
       ('Arab-pop', 'arab-pop', 'Arab-Pop'),
       ('Experimental-pop', 'experimental-pop', 'Experimental-Pop'),
       ('Folk-pop', 'folk-pop', 'Folk-Pop'),
       ('Noise-pop', 'noise-pop', 'Noise-Pop'),
       ('Garage-punk', 'garage-punk', 'Garage-Punk'),
       ('Egg-punk', 'egg-punk', 'Egg-Punk'),
       ('No-wave', 'no-wave', 'No-Wave'),
       ('Uk Bass', 'uk-bass', 'UK Bass'),
       ('ElectroClash', 'electroclash', 'Electroclash'),
       ('Psych.Soul', 'psych-soul', 'Psych-Soul');

UPDATE genre_tag g
SET name       = r.new_name,
    updated_at = now()
FROM genre_tag_respell r
WHERE g.slug = r.slug
  AND g.name = r.old_name;

CREATE TEMPORARY TABLE genre_tag_fold (old_slug text, new_slug text, new_name text, new_family text) ON COMMIT DROP;
INSERT INTO genre_tag_fold
VALUES ('dreampop', 'dream-pop', 'Dream-Pop', 'pop'),
       ('hardrock', 'hard-rock', 'Hard-Rock', 'rock'),
       ('jazzrock', 'jazz-rock', 'Jazz-Rock', 'rock'),
       ('antifolk', 'anti-folk', 'Anti-Folk', 'folk'),
       ('psychfolk', 'psych-folk', 'Psych-Folk', 'folk'),
       ('neopsychedelia', 'neo-psychedelia', 'Neo-Psychedelia', 'rock'),
       ('deathdoom', 'death-doom', 'Death-Doom', 'metal'),
       ('afrocuban', 'afro-cuban', 'Afro-Cuban', 'latin-world'),
       ('japanese-citypop', 'city-pop', 'City-Pop', 'pop'),
       ('punk-ska', 'ska-punk', 'Ska-Punk', 'punk'),
       ('cinematic-dream-pop', 'dream-pop', 'Dream-Pop', 'pop'),
       ('noise-dream-pop', 'dream-pop', 'Dream-Pop', 'pop'),
       ('exp-indie-rock', 'indie', 'Indie', 'rock'),
       ('exp-indie', 'indie', 'Indie', 'rock'),
       ('lofi-indie-pop', 'indie', 'Indie', 'rock'),
       ('indie-musik', 'indie', 'Indie', 'rock'),
       ('collegerock', 'indie', 'Indie', 'rock'),
       ('exp-prog-rock', 'prog-rock', 'Prog-Rock', 'rock'),
       ('70s-prog-rock', 'prog-rock', 'Prog-Rock', 'rock'),
       ('fuzz-noise-rock', 'noise-rock', 'Noise-Rock', 'rock'),
       ('grunge-gaze', 'shoegaze', 'Shoegaze', 'rock'),
       ('grunge-rock', 'grunge', 'Grunge', 'rock'),
       ('glam', 'glam-rock', 'Glam-Rock', 'rock'),
       ('math', 'math-rock', 'Math-Rock', 'rock'),
       ('psych', 'psychedelic', 'Psychedelic', 'rock'),
       ('psychedelia', 'psychedelic', 'Psychedelic', 'rock'),
       ('psychedelic-sounds', 'psychedelic', 'Psychedelic', 'rock'),
       ('psychrock', 'psychedelic', 'Psychedelic', 'rock'),
       ('queer-psych-rock', 'psychedelic', 'Psychedelic', 'rock'),
       ('psych-pop', 'psychedelic', 'Psychedelic', 'rock'),
       ('surf-beat', 'surf', 'Surf', 'rock'),
       ('60s-beat', 'beat', 'Beat', 'rock'),
       ('groovy-beat', 'beat', 'Beat', 'rock'),
       ('funkrockpop', 'pop', 'Pop', 'pop'),
       ('alternative-pop', 'pop', 'Pop', 'pop'),
       ('ghost-pop', 'pop', 'Pop', 'pop'),
       ('poetic-country-rock', 'country-rock', 'Country-Rock', 'rock'),
       ('country-blues-rock', 'country-rock', 'Country-Rock', 'rock'),
       ('folk-country-indie', 'folk', 'Folk', 'folk'),
       ('exp-folk', 'folk', 'Folk', 'folk'),
       ('country-bluegrass', 'bluegrass', 'Bluegrass', 'folk'),
       ('songwriter', 'singer-songwriter', 'Singer-Songwriter', 'folk'),
       ('blues-trash', 'blues', 'Blues', 'jazz-blues'),
       ('funk-jazz', 'jazz', 'Jazz', 'jazz-blues'),
       ('art-funk', 'funk', 'Funk', 'soul-funk'),
       ('neo-funk-soul', 'soul', 'Soul', 'soul-funk'),
       ('rockin-soul', 'soul', 'Soul', 'soul-funk'),
       ('indie-schlager', 'schlager', 'Schlager', 'pop'),
       ('pop-schlager', 'schlager', 'Schlager', 'pop'),
       ('schlagerpop', 'schlager', 'Schlager', 'pop'),
       ('partyschlager', 'schlager', 'Schlager', 'pop'),
       ('synthie', 'synthpop', 'Synthpop', 'pop'),
       ('bewegungs-punk', 'punk', 'Punk', 'punk'),
       ('solo-punk', 'punk', 'Punk', 'punk'),
       ('solar-punk', 'punk', 'Punk', 'punk'),
       ('astro-punk', 'punk', 'Punk', 'punk'),
       ('cold-punk', 'punk', 'Punk', 'punk'),
       ('powerpunk', 'punk', 'Punk', 'punk'),
       ('punk-pop', 'punk', 'Punk', 'punk'),
       ('akustikpunk', 'folk-punk', 'Folk-Punk', 'punk'),
       ('noisy-jazz-punk', 'punk', 'Punk', 'punk'),
       ('rnr-metal-punk', 'punk', 'Punk', 'punk'),
       ('metal-hc-punk', 'punk', 'Punk', 'punk'),
       ('post-digital-punk', 'post-punk', 'Post-Punk', 'wave'),
       ('garage-post-punk', 'post-punk', 'Post-Punk', 'wave'),
       ('shoegaze-post-punk', 'post-punk', 'Post-Punk', 'wave'),
       ('indie-punk-noise', 'indie-punk', 'Indie-Punk', 'punk'),
       ('garage-punk-psych', 'garage-punk', 'Garage-Punk', 'punk'),
       ('jamaican-ska', 'ska', 'Ska', 'punk'),
       ('sludge-hardcore', 'hardcore', 'Hardcore', 'punk'),
       ('stench-crust', 'crust', 'Crust', 'punk'),
       ('beatdown', 'beatdown-hardcore', 'Beatdown Hardcore', 'punk'),
       ('blackdeathdoom', 'death-doom', 'Death-Doom', 'metal'),
       ('rock-metal', 'metal', 'Metal', 'metal'),
       ('heavy-noise', 'noise', 'Noise', 'electronic'),
       ('nndw', 'ndw', 'NDW', 'wave'),
       ('goth', 'gothic-rock', 'Gothic Rock', 'wave'),
       ('breaks', 'breakbeat', 'Breakbeat', 'electronic'),
       ('breakbeats', 'breakbeat', 'Breakbeat', 'electronic'),
       ('bass-music', 'bass', 'Bass', 'electronic'),
       ('electronics', 'electronic', 'Electronic', 'electronic'),
       ('electro-minimal', 'minimal', 'Minimal', 'electronic'),
       ('minimalistic', 'minimal', 'Minimal', 'electronic'),
       ('deep-electro', 'electro', 'Electro', 'electronic'),
       ('dark-electronics', 'dark-electro', 'Dark Electro', 'electronic'),
       ('jazzy-breaks-house', 'house', 'House', 'electronic'),
       ('afro-house', 'house', 'House', 'electronic'),
       ('leftfield-grooves', 'leftfield', 'Leftfield', 'electronic'),
       ('dada-synth-lofi', 'lofi', 'Lofi', 'electronic'),
       ('indie-electronic', 'indietronica', 'Indietronica', 'electronic'),
       ('club-rap', 'hip-hop', 'Hip Hop', 'hip-hop'),
       ('live-beatbox', 'beatbox', 'Beatbox', 'hip-hop'),
       ('balkan-beats', 'balkan', 'Balkan', 'latin-world'),
       ('bossa', 'latin', 'Latin', 'latin-world'),
       ('neo-mexican', 'latin', 'Latin', 'latin-world'),
       ('rock-n-roll-cabaret', 'rock-n-roll', 'Rock''n''Roll', 'rock');

-- One rename per survivor at most: the first old tag takes the name, and the others fold onto it below.
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
WHERE slug IN (SELECT old_slug FROM genre_tag_fold);
