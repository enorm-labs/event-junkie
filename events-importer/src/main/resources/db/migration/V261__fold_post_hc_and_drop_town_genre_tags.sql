-- Schokoladen bills `Slow Mass (Post-HC/Indie Rock, Chicago)`, and the normalizer kept `Post-HC` as a tag beside
-- `Post Hardcore` (event 14562). It now maps both spellings onto `Post Hardcore`, so the stored links move, as in V257,
-- and a database with no `post-hardcore` renames `post-hc` instead.
--
-- `Dresden` and `Neuruppin` are the towns of `Dosenstolz (Punk/Dresden)` and `Eddie (Punk/Neuruppin)` (event 14564).
-- The parser now reads them as places, and `/api/genres` lists every `genre_tag` row, so the two tags go.
-- `event_genre_tag` follows a tag by `ON DELETE CASCADE`.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

UPDATE genre_tag
SET name       = 'Post Hardcore',
    slug       = 'post-hardcore',
    family     = 'punk',
    updated_at = now()
WHERE slug = 'post-hc'
  AND NOT EXISTS (SELECT 1 FROM genre_tag held WHERE held.slug = 'post-hardcore');

INSERT INTO event_genre_tag (event_id, genre_tag_id, origin)
SELECT egt.event_id, target.id, egt.origin
FROM event_genre_tag egt
         JOIN genre_tag old ON old.id = egt.genre_tag_id AND old.slug = 'post-hc'
         CROSS JOIN genre_tag target
WHERE target.slug = 'post-hardcore'
ON CONFLICT (event_id, genre_tag_id) DO NOTHING;

DELETE
FROM genre_tag
WHERE slug IN ('post-hc', 'dresden', 'neuruppin');
