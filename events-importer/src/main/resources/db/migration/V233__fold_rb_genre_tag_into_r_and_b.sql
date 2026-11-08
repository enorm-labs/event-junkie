-- Huxleys' genre slug `rb` drops the `&` of R&B, and the importer stored it as a tag of its own, `Rb` (#4694). The
-- normalizer now maps `rb` to R&B, but stored links keep the old tag, and `/api/genres` lists every `genre_tag` row.
-- So this moves each link from `rb` to `r-b` and deletes `rb`. A database with no `r-b` renames `rb` instead.
--
-- The stored `genre` text of the linked events loses its `Rb` token too, matched as a whole comma-separated token.
-- `event_genre_tag` keeps one row per event and tag, so a link `r-b` already holds is dropped with `rb`.
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

UPDATE event e
SET genre      = regexp_replace(e.genre, '(^|,)(\s*)Rb(\s*)(?=,|$)', '\1\2R&B\3', 'g'),
    updated_at = now()
FROM event_genre_tag egt
         JOIN genre_tag rb ON rb.id = egt.genre_tag_id
WHERE egt.event_id = e.id
  AND rb.slug = 'rb'
  AND e.genre ~ '(^|,)\s*Rb\s*(,|$)';

UPDATE genre_tag
SET name       = 'R&B',
    slug       = 'r-b',
    updated_at = now()
WHERE slug = 'rb'
  AND NOT EXISTS (SELECT 1 FROM genre_tag held WHERE held.slug = 'r-b');

INSERT INTO event_genre_tag (event_id, genre_tag_id, origin)
SELECT egt.event_id, rnb.id, egt.origin
FROM event_genre_tag egt
         JOIN genre_tag rb ON rb.id = egt.genre_tag_id AND rb.slug = 'rb'
         CROSS JOIN genre_tag rnb
WHERE rnb.slug = 'r-b'
ON CONFLICT (event_id, genre_tag_id) DO NOTHING;

-- `event_genre_tag` follows the tag by `ON DELETE CASCADE`.
DELETE
FROM genre_tag
WHERE slug = 'rb';
