-- Venues wrote one decade four ways, and each spelling became a tag of its own: `2000er`, `00s` and `y2k` beside
-- `2000s`, and `swing-dance` beside `swing`. `christmas-iconics` names a theme, not a style. The normalizer now folds
-- the spellings and drops the theme, but stored links keep the old tags, and `/api/genres` lists every `genre_tag` row.
-- So this moves each link onto the canonical tag and deletes the old one. A database with no canonical tag renames.
--
-- Junction Bar's DOSIS bills itself in capitals, and the de-shouter stored `Dosis`. An import does not rename an
-- existing row, so the name is restored here, keyed on the old name so a hand correction stays.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE genre_tag_fold (old_slug text, new_slug text, new_name text, new_family text) ON COMMIT DROP;
INSERT INTO genre_tag_fold
VALUES ('2000er', '2000s', '2000s', 'charts'),
       ('00s', '2000s', '2000s', 'charts'),
       ('y2k', '2000s', '2000s', 'charts'),
       ('80er', '80s', '80s', 'charts'),
       ('90er', '90s', '90s', 'charts'),
       ('swing-dance', 'swing', 'Swing', 'jazz-blues');

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

-- `event_genre_tag` follows the tag by `ON DELETE CASCADE`.
DELETE
FROM genre_tag
WHERE slug IN (SELECT old_slug FROM genre_tag_fold)
   OR slug = 'christmas-iconics';

UPDATE artist
SET name = 'DOSIS'
WHERE slug = 'dosis'
  AND name = 'Dosis';
