-- The artist row that names Heimathafen's performance evening `Spot On! The Show`, not an act (#4333). The parser now drops
-- the name. One night is past, and a past night is never imported again, so the row keeps its link and OrphanArtistSweep
-- never removes it.

SELECT drop_artist_billed_only_by('spot-on-the-show', 'heimathafen:');
