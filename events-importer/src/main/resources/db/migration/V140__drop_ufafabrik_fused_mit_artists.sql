-- Four artist rows that fuse ufaFabrik's `<A> mit <B>` billing or keep a `Special Guest` label in the name (#3106). The
-- parser now bills each person and the guest as support. A past night is never imported again, so a row keeps its link
-- and OrphanArtistSweep never removes it. The next import bills the upcoming nights afresh.

SELECT drop_artist_billed_only_by('nils-kercher-mit-kira-kaipainen', 'ufa_fabrik:');
SELECT drop_artist_billed_only_by('special-guest-oumar-barou-kouyate', 'ufa_fabrik:');
SELECT drop_artist_billed_only_by('ulla-meinecke-mit-reinmar-henschke', 'ufa_fabrik:');
SELECT drop_artist_billed_only_by('manouche-26-special-guest-dotschy-reinhardt', 'ufa_fabrik:');
