-- BKA Theater's duos `Jacky und Mahoni` and `Evers & Eichhorn` were split into one-word acts until #3600 billed them whole.
-- The halves `Mahoni`, `Evers` and `Eichhorn` then bill nothing, but each holds an EXACT MusicBrainz match, and the
-- unbilled-artist sweep keeps every EXACT row, so their public pages stay (#3606).

SELECT drop_artist_billed_only_by('mahoni', 'bka_theater:');
SELECT drop_artist_billed_only_by('evers', 'bka_theater:');
SELECT drop_artist_billed_only_by('eichhorn', 'bka_theater:');
