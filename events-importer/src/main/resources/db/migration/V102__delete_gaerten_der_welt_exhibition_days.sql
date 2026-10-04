-- Gärten der Welt's exhibition "Zwischen Himmel und Erde" is stored as one row per day (#2655).
-- The listing shows the exhibition once, with the whole run in its date cell, and links the next
-- open day only. The importer took the day from that link, so each import added a row
-- `gaerten_der_welt:<yyyy-mm-dd>_<hhmm>/<slug>`. It now stores the run once, as
-- `gaerten_der_welt:<slug>` from 2026-09-01 to 2026-11-01.
--
-- The past days are never imported again, so the stale cleanup does not reach them. This deletes
-- every daily row of the run. The pattern names the slug, so a concert keeps its dated `sourceId`.
-- The run row, which has no day in its `sourceId`, does not match. The next import inserts it.
--
-- `event_artist`, `event_promoter`, `event_genre_tag` and `event_quality_flag` follow the event by
-- `ON DELETE CASCADE`. `cached_image` holds no reference to an event.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM event
WHERE source_id ~ '^gaerten_der_welt:\d{4}-\d{2}-\d{2}_\d{4}/zwischen-himmel-und-erde-ausstellung$';
