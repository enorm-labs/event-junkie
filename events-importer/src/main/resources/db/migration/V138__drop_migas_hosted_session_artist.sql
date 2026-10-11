-- migas billed its hosted listening session as one artist named after the whole title (#3099). The parser now bills no
-- act for a `, presented by <host>` title. A past night is never imported again, so the row keeps its link.

SELECT drop_artist_billed_only_by('the-music-of-john-cage-chance-and-indeterminate-composition-presented-by-jordan-elias', 'migas:');
