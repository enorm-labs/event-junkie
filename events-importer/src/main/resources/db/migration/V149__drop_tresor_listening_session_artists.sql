-- Tresor bills a record playback on the Globus floor as `Listening Session: <release>`, and the parser stored the release as
-- an act: a catalogue number (`Tresor.291 - Dreamy Harbour`, `Tresor048X Infiniti Collection`) or a compilation. Nobody
-- performs at a listening session, so the parser now drops the slot. A past night is never imported again, so the rows
-- keep their links unless they are removed here.

SELECT drop_artist_billed_only_by('listening-session-borderland-tresor-262', 'tresor:');
SELECT drop_artist_billed_only_by('tresor-291-dreamy-harbour', 'tresor:');
SELECT drop_artist_billed_only_by('tresor-299-xor-gate-conic-sections', 'tresor:');
SELECT drop_artist_billed_only_by('tresor048x-infiniti-collection', 'tresor:');
SELECT drop_artist_billed_only_by('oscean-collection', 'tresor:');
