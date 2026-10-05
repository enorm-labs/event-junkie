-- Five artist rows that are a work, its year, its length and its instrumentation, not acts (#2705). Morphine Raum's
-- 2026-10-06 set line `Mads Emil Dreyer - Repeater, Quietus (2024), 35’ For 8 percussion instruments, feedback system
-- and lights` was split on its commas. The parser now keeps the act before a dash tail that dates or times a work. A
-- past night is never imported again, so the rows keep their links and OrphanArtistSweep never removes them.

SELECT drop_artist_billed_only_by('mads-emil-dreyer-repeater', 'morphine:');
SELECT drop_artist_billed_only_by('quietus-2024', 'morphine:');
SELECT drop_artist_billed_only_by('35-for-8-percussion-instruments', 'morphine:');
SELECT drop_artist_billed_only_by('feedback-system', 'morphine:');
SELECT drop_artist_billed_only_by('lights', 'morphine:');
