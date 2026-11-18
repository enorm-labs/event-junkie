-- `Oberbillig` is the village of `DORFTERROR (Punkrock/Oberbillig)` at Schokoladen, which the parser kept as a genre.
-- The parser now reads it as a place, as V261 did for `Dresden` and `Neuruppin`. `/api/genres` lists every `genre_tag`
-- row, so the tag goes. `event_genre_tag` follows a tag by `ON DELETE CASCADE`.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

DELETE
FROM genre_tag
WHERE slug = 'oberbillig';
