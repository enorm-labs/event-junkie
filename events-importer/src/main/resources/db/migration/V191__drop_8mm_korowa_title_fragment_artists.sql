-- 8MM Bar's night title `KOROWA — A Night of Live and Dark Sounds` was split at its `and`, and the halves were minted as two
-- acts (#3829). The parser now bills only a title in the house frame. The night of 2026-10-23 is past and is never imported
-- again, so the two rows are dropped here.

SELECT drop_artist_billed_only_by('korowa-a-night-of', 'eight_mm:');
SELECT drop_artist_billed_only_by('dark-sounds', 'eight_mm:');
