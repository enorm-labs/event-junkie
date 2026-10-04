-- Reads every EXACT artist's MusicBrainz entity again, for its letter case (#2317).
--
-- The enrichment now stores MusicBrainz's spelling when it differs from the stored name in letter
-- case only: `Nvst` becomes `NVST`. The backfill already read these rows, and the database does not
-- hold the MusicBrainz name, so every EXACT row goes back into it once. Apart from the name, the read
-- fills only empty columns, so a row that gains nothing is stamped and left as it was.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

UPDATE artist
SET musicbrainz_enriched_at = NULL
WHERE musicbrainz_match = 'EXACT'
  AND musicbrainz_enriched_at IS NOT NULL;
