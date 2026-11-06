-- The artist row that names Huxleys' package tour `Rock Legends`, not an act (#4513). The parser now drops the name. Its one
-- night is past after 2026-11-06, and a past night is never imported again, so the row keeps its link and OrphanArtistSweep
-- never removes it.

SELECT drop_artist_billed_only_by('rock-legends', 'huxleys:');
