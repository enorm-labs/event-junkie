-- The artist row that names Heimathafen's karaoke format `Sing dela Sing`, not an act (#2788). The parser now types the
-- night a party and drops the name. Three nights are past, and a past night is never imported again, so the row keeps
-- its link and OrphanArtistSweep never removes it.

SELECT drop_artist_billed_only_by('sing-dela-sing', 'heimathafen:');
