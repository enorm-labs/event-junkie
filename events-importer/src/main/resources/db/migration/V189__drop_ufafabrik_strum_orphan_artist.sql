-- ufaFabrik's sing-along `Berliner Ukulele Sing & Strum` was split at its `&`, and the half `Strum` was minted as an act (#3731).
-- The night bills nobody now, but `Strum` holds an EXACT MusicBrainz match, and the unbilled-artist sweep keeps every EXACT row,
-- so its public page stays. A past night is never re-imported, so no import removes it.

SELECT drop_artist_billed_only_by('strum', 'ufa_fabrik:');
