-- A-Trane's 2026-10-10 night headed its name with the banner `LETZTEN PLÄTZE AN DER BAR-LAST SEATS LEFT AT THE BAR`,
-- and the parser took it as the title and the act (#3165). The parser now drops the banner. The night is past, and a
-- past night is never imported again, so the row is corrected here. Its source_id is long, so it is matched by its
-- prefix and the date; the slug stays, because it is already published.

UPDATE event
SET title    = 'DAVID HAYNES AND FRIENDS',
    subtitle = regexp_replace(subtitle, '^DAVID HAYNES AND FRIENDS · ', '')
WHERE starts_with(source_id, 'a_trane:a-trane-presents-day2david-haynes-and-friends')
  AND event_date = DATE '2026-10-10';

DELETE FROM event_artist ea
USING event e, artist a
WHERE ea.event_id = e.id
  AND ea.artist_id = a.id
  AND starts_with(e.source_id, 'a_trane:a-trane-presents-day2david-haynes-and-friends')
  AND e.event_date = DATE '2026-10-10'
  AND a.slug = 'letzten-platze-an-der-bar-last-seats-left-at-the-bar';

SELECT drop_artist_billed_only_by('letzten-platze-an-der-bar-last-seats-left-at-the-bar', 'a_trane:');

INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived)
SELECT e.id, a.id, 'HEADLINER', 0, TRUE
FROM event e, artist a
WHERE starts_with(e.source_id, 'a_trane:a-trane-presents-day2david-haynes-and-friends')
  AND e.event_date = DATE '2026-10-10'
  AND a.slug = 'david-haynes-and-friends'
ON CONFLICT (event_id, artist_id) DO NOTHING;
