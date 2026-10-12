-- SO36's monthly KIEZBINGO was typed a concert and billed `Kiezbingo` as its act (#3149). The parser now reads a
-- bingo title as SHOW and bills no act. The 2026-10-13 night is past, and a past night is never imported again, so
-- its row keeps the old type unless corrected here.

UPDATE event
SET event_type = 'SHOW'
WHERE source_id = 'so36:100089';

SELECT drop_artist_billed_only_by('kiezbingo', 'so36:');
