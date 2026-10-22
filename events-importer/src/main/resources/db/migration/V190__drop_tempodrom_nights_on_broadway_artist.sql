-- Tempodrom billed the show title `Nights on Broadway` as the act, and its format line names the band: `performed by NIGHT FEVER`
-- (#3746). The scraper now bills `Night Fever`, but both nights were past before the fix shipped, and a past night is never
-- re-imported, so no import removes the show's artist page.

SELECT drop_artist_billed_only_by('nights-on-broadway', 'tempodrom:');
