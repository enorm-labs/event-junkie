-- SlugGenerator now folds the typographic apostrophes `’`, `‘` and `ʼ` to `'` (#4904). Slugify turned a straight `'` into `-`
-- but dropped a typographic one, so `DJ R'n'P` slugged to `dj-r-n-p` and `DJ R’n’P` to `dj-rnp`: one act, two artist rows.
-- The import finds an artist and a genre tag by slug, so a stored row under the old slug would be missed and minted again.
-- This gives the stored rows the slug the new rule gives them.
--
-- `pg_temp.slug_4904`, gone with the session, repeats SlugGenerator's Latin path: its replacements, NFKD, non-ASCII
-- dropped, the rest of `\W` as `-`. A row is re-slugged only when the old rule reproduces its stored slug. So a Cyrillic
-- or Greek name, a slug from an override and a hand-set slug are left alone.
--
-- Artists: a row whose new slug another artist holds, as an artist or an alias, merges into that artist, in V225's shape.
-- A renamed artist keeps its old slug as an alias (V159), so an old link still finds it.
-- Genre tags: a tag whose new slug another tag holds moves its links onto that tag and goes, in V242's shape.
-- Events: an event slug is recomputed at every import, so only the events an import still reads are re-slugged: those
-- that end today or later, and last night's. A past event keeps its URL. An event whose new slug is taken keeps its slug.
-- A source id built from the title (`dateTitleId`) changes with it.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE FUNCTION pg_temp.slug_4904(input TEXT, folded BOOLEAN) RETURNS TEXT
    LANGUAGE plpgsql
    IMMUTABLE
    STRICT
AS
$$
DECLARE
    result TEXT := btrim(input);
    pair   TEXT[];
BEGIN
    IF folded THEN
        result := translate(result, U&'\2019\2018\02BC', '''''''');
    END IF;
    FOREACH pair SLICE 1 IN ARRAY ARRAY [['ø', 'o'], ['Ø', 'O'], ['æ', 'ae'], ['Æ', 'Ae'], ['ð', 'd'], ['Ð', 'D'], ['þ', 'th'],
        ['Þ', 'Th'], ['ł', 'l'], ['Ł', 'L'], ['đ', 'd'], ['Đ', 'D'], ['ı', 'i'], ['ß', 'ss'], ['ẞ', 'Ss'], ['œ', 'oe'],
        ['Œ', 'Oe'], ['_', ' ']]
        LOOP
            result := replace(result, pair[1], pair[2]);
        END LOOP;
    result := regexp_replace(normalize(result, NFKD), '[^\x01-\x7F]+', '', 'g');
    result := regexp_replace(result, '[^A-Za-z0-9_]+', '-', 'g');
    RETURN lower(regexp_replace(result, '^-|-$', '', 'g'));
END;
$$;

-- 1. The artists the new rule re-slugs, and the artist each one ends as: the holder of the new slug, else the alias's
--    artist, else the smallest id among the rows that share the new slug.
CREATE TEMPORARY TABLE artist_reslug ON COMMIT DROP AS
SELECT a.id, a.slug AS old_slug, pg_temp.slug_4904(a.name, true) AS new_slug
FROM artist a
WHERE a.name ~ U&'[\2019\2018\02BC]'
  AND pg_temp.slug_4904(a.name, false) = a.slug
  AND pg_temp.slug_4904(a.name, true) NOT IN (a.slug, '');

CREATE TEMPORARY TABLE artist_move ON COMMIT DROP AS
SELECT r.id AS loser_id, r.old_slug AS loser_slug, s.survivor_id
FROM artist_reslug r
JOIN (SELECT new_slug,
             COALESCE((SELECT held.id FROM artist held WHERE held.slug = new_slug),
                      (SELECT alias.artist_id FROM artist_slug_alias alias WHERE alias.slug = new_slug),
                      MIN(id)) AS survivor_id
      FROM artist_reslug
      GROUP BY new_slug) s ON s.new_slug = r.new_slug
WHERE r.id <> s.survivor_id;

-- 2. A loser's links, moved onto its survivor with role, billing order, stage and set times. A night that bills both keeps
--    the survivor's own link.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, mv.survivor_id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM event_artist ea
JOIN artist_move mv ON mv.loser_id = ea.artist_id
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 3. Followers, moved unless the account follows the survivor already. A follow names the artist by id, with no foreign
--    key (V195).
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

-- 4. A loser's former slugs move onto its survivor, the losers go with their links, and each loser's slug becomes an alias.
UPDATE artist_slug_alias alias
SET artist_id = mv.survivor_id
FROM artist_move mv
WHERE alias.artist_id = mv.loser_id;

DELETE FROM artist l
USING artist_move mv
WHERE l.id = mv.loser_id;

INSERT INTO artist_slug_alias (slug, artist_id)
SELECT loser_slug, survivor_id
FROM artist_move
ON CONFLICT (slug) DO NOTHING;

-- 5. The artists that remain take the new slug and keep the old one as an alias.
INSERT INTO artist_slug_alias (slug, artist_id)
SELECT r.old_slug, r.id
FROM artist_reslug r
JOIN artist a ON a.id = r.id
WHERE NOT EXISTS (SELECT 1 FROM artist held WHERE held.slug = r.new_slug)
ON CONFLICT (slug) DO NOTHING;

DELETE FROM artist_slug_alias alias
USING artist_reslug r
WHERE alias.slug = r.new_slug
  AND alias.artist_id = r.id;

UPDATE artist a
SET slug = r.new_slug
FROM artist_reslug r
WHERE a.id = r.id
  AND NOT EXISTS (SELECT 1 FROM artist held WHERE held.slug = r.new_slug);

-- The aliases of a typographic-apostrophe billing, under the slug that billing has now: V160's for Huxleys' `ORGI’S
-- WEIHNACHTSSHOW`, and production's for `Stoner’s Fall` + Sheev. An alias has no name to slug again.
INSERT INTO artist_slug_alias (slug, artist_id)
SELECT renamed.new_slug, alias.artist_id
FROM (VALUES ('orgis-weihnachtsshow', 'orgi-s-weihnachtsshow'),
             ('stoners-fall-sheev', 'stoner-s-fall-sheev')) AS renamed (old_slug, new_slug)
JOIN artist_slug_alias alias ON alias.slug = renamed.old_slug
WHERE NOT EXISTS (SELECT 1 FROM artist held WHERE held.slug = renamed.new_slug)
ON CONFLICT (slug) DO NOTHING;

-- 6. Genre tags: the first tag on a new slug that nobody holds takes it, and the links of every other one move onto the
--    holder. `event_genre_tag` follows a deleted tag by `ON DELETE CASCADE`.
CREATE TEMPORARY TABLE genre_tag_reslug ON COMMIT DROP AS
SELECT g.id, pg_temp.slug_4904(g.name, true) AS new_slug
FROM genre_tag g
WHERE g.name ~ U&'[\2019\2018\02BC]'
  AND pg_temp.slug_4904(g.name, false) = g.slug
  AND pg_temp.slug_4904(g.name, true) NOT IN (g.slug, '');

UPDATE genre_tag g
SET slug       = r.new_slug,
    updated_at = now()
FROM (SELECT DISTINCT ON (new_slug) id, new_slug FROM genre_tag_reslug ORDER BY new_slug, id) r
WHERE g.id = r.id
  AND NOT EXISTS (SELECT 1 FROM genre_tag held WHERE held.slug = r.new_slug);

INSERT INTO event_genre_tag (event_id, genre_tag_id, origin)
SELECT egt.event_id, target.id, egt.origin
FROM event_genre_tag egt
JOIN genre_tag_reslug r ON r.id = egt.genre_tag_id
JOIN genre_tag target ON target.slug = r.new_slug
WHERE target.id <> r.id
ON CONFLICT (event_id, genre_tag_id) DO NOTHING;

DELETE FROM genre_tag g
USING genre_tag_reslug r
WHERE g.id = r.id
  AND g.slug <> r.new_slug;

-- 7. Upcoming events: the slug is `<date>-<venue slug>-<title>`, and a discriminator after it stays as it is.
CREATE TEMPORARY TABLE upcoming_event ON COMMIT DROP AS
SELECT e.id,
       e.slug,
       e.source_id,
       to_char(e.event_date, 'YYYY-MM-DD') AS day,
       pg_temp.slug_4904(to_char(e.event_date, 'YYYY-MM-DD') || '-' || v.slug || '-' || e.title, false) AS old_base,
       pg_temp.slug_4904(to_char(e.event_date, 'YYYY-MM-DD') || '-' || v.slug || '-' || e.title, true) AS new_base,
       pg_temp.slug_4904(e.title, false) AS old_title,
       pg_temp.slug_4904(e.title, true) AS new_title
FROM event e
JOIN venue v ON v.id = e.venue_id
WHERE e.title ~ U&'[\2019\2018\02BC]'
  AND COALESCE(e.end_date, e.event_date) >= CURRENT_DATE - 1;

CREATE TEMPORARY TABLE event_reslug ON COMMIT DROP AS
SELECT id, new_base || substr(slug, length(old_base) + 1) AS new_slug
FROM upcoming_event
WHERE old_base <> new_base
  AND (slug = old_base OR starts_with(slug, old_base || '-'));

UPDATE event e
SET slug = r.new_slug
FROM event_reslug r
WHERE e.id = r.id
  AND NOT EXISTS (SELECT 1 FROM event held WHERE held.slug = r.new_slug)
  AND (SELECT count(*) FROM event_reslug twin WHERE twin.new_slug = r.new_slug) = 1;

-- 8. A source with no id of its own keys a row by `<date>-<title slug>` (`dateTitleId`), and that key changes too. Without
--    the new key the next import would delete the stored row as stale and insert the event again under a new id. Only the
--    sources that call `dateTitleId`: Hole 44, Huxleys and Metropol key by the venue's own URL slug, which looks the same.
CREATE TEMPORARY TABLE event_rekey ON COMMIT DROP AS
SELECT id, left(source_id, length(source_id) - length(old_title)) || new_title AS new_source_id
FROM upcoming_event
WHERE old_title <> new_title
  AND split_part(source_id, ':', 1) IN ('aeden', 'arcanoa', 'ballhaus_wedding', 'der_weisse_hase', 'duncker', 'frachtkante',
                                        'gartn', 'golden_gate', 'insel', 'karakas', 'kuehlspot', 'maaya', 'matrix', 'monarch',
                                        'ohm', 'orangerie_neukoelln', 'peter_edel', 'renate', 'roadrunner', 'studio_iiii',
                                        'tausend', 'void_club', 'wabe', 'wild_at_heart', 'z_bar', 'zosch')
  AND source_id = split_part(source_id, ':', 1) || ':' || day || '-' || old_title;

UPDATE event e
SET source_id = r.new_source_id
FROM event_rekey r
WHERE e.id = r.id
  AND NOT EXISTS (SELECT 1 FROM event held WHERE held.source_id = r.new_source_id)
  AND (SELECT count(*) FROM event_rekey twin WHERE twin.new_source_id = r.new_source_id) = 1;
