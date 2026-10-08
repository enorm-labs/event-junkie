-- VOID Club's `INFECTED DNB PRES. ZIGI SC ALBUM TOUR` was dated 2026-10-09 from a stale aria-label, while the card
-- shows 2026-10-24 (#2898). The parser now reads the shown date, but the stale cleanup never removes a past row.
--
-- `event_artist` and the other join tables follow by `ON DELETE CASCADE`. The artists stay, because the forced import
-- bills them again on 24 October. The statement does nothing when a forced import already removed the row.

DELETE FROM event
WHERE source_id = 'void_club:2026-10-09-infected-dnb-pres-zigi-sc-album-tour';
