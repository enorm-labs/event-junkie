-- Three billing defects stored artists no act is called (#2958, #2959, #2960). Neue Zukunft's support band `Prophet & Flesh`
-- was split into `Prophet` and `Flesh`, Urban Spree's series `Indie in Town` stayed on its first act, and Crack Bellmer's
-- `(Album Set)` stayed in the name. The rules now bill the real acts. The next forced import re-bills each event.

SELECT drop_artist_billed_only_by('prophet', 'neue_zukunft:');
SELECT drop_artist_billed_only_by('flesh', 'neue_zukunft:');
SELECT drop_artist_billed_only_by('indie-in-town-vence', 'urban_spree:');
SELECT drop_artist_billed_only_by('sonny-smiles-album-set', 'crack_bellmer:');
