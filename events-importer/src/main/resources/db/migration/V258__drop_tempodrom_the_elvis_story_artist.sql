-- Tempodrom billed the production title `The Elvis Story` as the act, and its tagline names the singer: `Featuring BOB DAWN
-- & Band` (#5193). The scraper now bills `Bob Dawn`. The 17.11.2026 night is postponed and past once this ships, and a past
-- night is never re-imported, so no import removes its link to the show's artist page. The 31.05.2027 night is re-billed by
-- the next import.

SELECT drop_artist_billed_only_by('the-elvis-story', 'tempodrom:');
