-- Badehaus's dance workshop `Café com Samba Berlin` was typed a concert and billed its own name as an act (#3100). The
-- parser now reads the venue's `Interactive` category as OTHER and drops the name. Both nights are past, and a past
-- night is never imported again, so the rows keep their link and type unless corrected here.

UPDATE event
SET event_type = 'OTHER'
WHERE source_id IN ('badehaus:cafe-com-samba-berlin', 'badehaus:cafe-com-samba-berlin-2');

SELECT drop_artist_billed_only_by('cafe-com-samba-berlin', 'badehaus:');
