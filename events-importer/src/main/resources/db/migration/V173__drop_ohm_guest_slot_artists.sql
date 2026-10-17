-- OHM billed four unnamed guest slots (`+ Guest 1` … `+ Guest 4`) on subglow, 18/10, and the parser stored each as a DJ.
-- The parser now drops the slot (#3512). The night is past before this runs and is never imported again, so the rows keep
-- their links unless they are removed here.

SELECT drop_artist_billed_only_by('guest-1', 'ohm:');
SELECT drop_artist_billed_only_by('guest-2', 'ohm:');
SELECT drop_artist_billed_only_by('guest-3', 'ohm:');
SELECT drop_artist_billed_only_by('guest-4', 'ohm:');
