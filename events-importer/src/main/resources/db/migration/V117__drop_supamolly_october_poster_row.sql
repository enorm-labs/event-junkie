-- Supamolly's October poster row `Oktober Supamolly`, imported as a concert with an artist of that name (#2825). The
-- parser now skips it, but the row is dated 2026-10-08, and the stale cleanup never removes a past row.
--
-- The event goes first, so the artist's only link is gone. `event_artist` and the other join tables follow by
-- `ON DELETE CASCADE`. Both statements do nothing when a forced import already removed the row.

DELETE FROM event
WHERE source_id = 'supamolly:202610082359';

SELECT drop_artist_billed_only_by('oktober-supamolly', 'supamolly:');
