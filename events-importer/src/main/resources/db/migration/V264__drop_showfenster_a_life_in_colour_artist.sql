-- Showfenster billed the work title `A Life In Colour` as the act, and the concert's page names the three players under
-- `Es spielen:` (#5238). The importer now reads that page and bills the players. The next import re-bills the 18.11.2026
-- night if it is still upcoming. A night already past is never re-imported, so no import removes its link to the title's
-- artist page.
--
-- The drop deletes the row with its links and its MusicBrainz match, and only where Showfenster alone bills it (V092).

SELECT drop_artist_billed_only_by('a-life-in-colour', 'showfenster:');
