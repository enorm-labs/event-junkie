-- Four artist rows that name a show, a series or a tour, not an act (#2709): the Wühlmäuse revue `Der Blaue Montag`,
-- the ufaFabrik showcase `Berlin Songwood Sessions`, the Mikropol series `Große Hip Hop Offensive 17` and the SO36 title
-- `Yasuharu Takanashi Tour`. The parser now drops the first three names and strips the tour word from the fourth.
-- A past night is never imported again, so each row keeps its link and OrphanArtistSweep never removes it.

SELECT drop_artist_billed_only_by('der-blaue-montag', 'wuehlmaeuse:');
SELECT drop_artist_billed_only_by('berlin-songwood-sessions', 'ufa_fabrik:');
SELECT drop_artist_billed_only_by('grosse-hip-hop-offensive-17', 'mikropol:');
SELECT drop_artist_billed_only_by('yasuharu-takanashi-tour', 'so36:');
