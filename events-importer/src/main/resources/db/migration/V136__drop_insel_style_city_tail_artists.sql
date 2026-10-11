-- Kulturhaus Insel's title `HKSPK (DE) + ALKHEMIA (FR) + MY LAMENT (BE) / Black & Doom Metal / Berlin` billed its style and
-- its city as three acts (#3096). The parser now bills the bands only and stores the style as the genre. The night is past
-- before the fix ships and is never imported again, so the three rows are dropped here.

SELECT drop_artist_billed_only_by('black', 'insel:');
SELECT drop_artist_billed_only_by('doom-metal', 'insel:');
SELECT drop_artist_billed_only_by('berlin', 'insel:');
