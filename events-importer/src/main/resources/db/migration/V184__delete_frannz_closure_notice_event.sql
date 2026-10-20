-- Frannz Club published "Frannz Biergarten bleibt heute geschlossen!" as an article, and it was stored as a concert
-- on 2026-09-07 (#3669). The parser now skips a closure notice, but the stale cleanup never removes a past row.
--
-- Every table that references `event` follows by `ON DELETE CASCADE`: the artist, promoter and genre links, quality
-- flags, suggestions, enrichment, features, the change log and the classification check. `instagram_draft` keeps its
-- row with `event_id` set to NULL. The title-derived artist stays: no event bills it afterwards, so the daily orphan
-- sweep deletes it. The statement does nothing on a database that never stored the row.

DELETE FROM event
WHERE slug = '2026-09-07-frannz-club-frannz-biergarten-bleibt-heute-geschlossen';
