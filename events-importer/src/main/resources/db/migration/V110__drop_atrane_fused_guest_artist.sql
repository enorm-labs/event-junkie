-- One artist row that fuses two guests of A-Trane's weekly session, not an act (#2706). The 2026-10-05 night
-- `ANDREAS SCHMIDT & FRIENDS / HEUTE MIT: ROLAND SCHNEIDER- CHRISTIAN KÖGEL` was stored with the guest line as its
-- title. The parser now keeps the act and bills each guest. A past night is never imported again, so the row keeps
-- its link and OrphanArtistSweep never removes it.

SELECT drop_artist_billed_only_by('roland-schneider-christian-kogel', 'a_trane:');
