-- Badehaus billed `Uats Live + Jam`, and the title split minted the jam session as the act `JAM` (#3160). The parser now
-- drops `jam` as a non-artist name. The night is past, and a past night is never imported again, so the row keeps the
-- link unless it is removed here.

SELECT drop_artist_billed_only_by('jam', 'badehaus:');
