-- Arcanoa titled a night by the hours leading its line, `19-21Uhr` (#2707). The importer now reads
-- those hours as the start and end and titles the night by the text after them, so its
-- `sourceId` changes to `arcanoa:<date>-songwriting-workshop-mit-dave-benjoya`.
--
-- The stale cleanup removes the future rows with the old `sourceId`, but not a past one: the
-- 2026-10-06 row stays. This deletes every row whose `sourceId` slug is only an hour range.
--
-- `event_artist`, `event_promoter`, `event_genre_tag` and `event_quality_flag` follow the event by
-- `ON DELETE CASCADE`. `cached_image` holds no reference to an event.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM event
WHERE source_id ~ '^arcanoa:\d{4}-\d{2}-\d{2}-\d{1,2}(-\d{1,2})?uhr$';
