-- The artist row that names Tempodrom's Wolfgang Petry show `WAHNSINN!`, not an act (#4341). The parser now bills no act
-- for a format line that bills a show of another artist's songs. The 2026-11-01 night is past and is never imported again,
-- so the row keeps its link and OrphanArtistSweep never removes it.

SELECT drop_artist_billed_only_by('wahnsinn', 'tempodrom:');
