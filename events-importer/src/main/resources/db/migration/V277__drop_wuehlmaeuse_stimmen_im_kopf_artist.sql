-- Die Wühlmäuse billed the podcast `Stimmen im Kopf` as the act of its live show (#5452). The importer now refuses the
-- name. The show of 23.11.2026 is past, so no import removes its link to the podcast's artist page.
--
-- The drop deletes the row with its links and its MusicBrainz match, and only where Die Wühlmäuse alone bills it (V092).

SELECT drop_artist_billed_only_by('stimmen-im-kopf', 'wuehlmaeuse:');
