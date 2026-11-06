-- The artist row that names ufaFabrik's series `Samba Made in Berlin`, not an act (#4514). The parser now drops the name. Both
-- nights are past by the time this runs, and a past night is never imported again, so the row keeps its link on the cancelled
-- night and OrphanArtistSweep never removes it. The other night bills its two acts by a pinned lineup, which this leaves alone.

SELECT drop_artist_billed_only_by('samba-made-in-berlin', 'ufa_fabrik:');
