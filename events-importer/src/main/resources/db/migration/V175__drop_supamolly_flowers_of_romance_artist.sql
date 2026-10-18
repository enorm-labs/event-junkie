-- Supamolly bills the night `Flowers of Romance` in an act slot, its DJ in the note, and the scraper stored the night as the
-- headliner (#3564). The night now bills DJ Terror Wave, but a past night is never imported again, so its link stays.

SELECT drop_artist_billed_only_by('flowers-of-romance', 'supamolly:');
