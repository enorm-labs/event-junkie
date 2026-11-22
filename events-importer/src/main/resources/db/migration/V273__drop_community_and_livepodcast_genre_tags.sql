-- `Community` is the ethos in Orangerie Neukölln's "house, community", and `Livepodcast` is the format BKA Theater files
-- in its genre field. Neither is a genre, so the normalizer now drops both words. `/api/genres` lists every `genre_tag`
-- row, so the tags go. `event_genre_tag` follows a tag by `ON DELETE CASCADE`.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

DELETE
FROM genre_tag
WHERE slug IN ('community', 'livepodcast');
