-- The artist row that names KitKatClub's unannounced lineup `Secret Music Line up`, not an act (#2901). The guest-slot
-- rule now drops the name. A past night is never imported again, so the row keeps its link and OrphanArtistSweep never
-- removes it.

SELECT drop_artist_billed_only_by('secret-music-line-up', 'kitkatclub:');
