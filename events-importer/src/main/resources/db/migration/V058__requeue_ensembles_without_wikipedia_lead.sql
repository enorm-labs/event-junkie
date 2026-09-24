-- Reads the ensembles without a description again, under the narrower birth-data rule (#1879).
--
-- A German lead that named a title with "Geboren" was refused as birth data, and with it the
-- other wiki's lead. The UPDATE puts every EXACT ensemble with a Wikidata link and no description
-- back into the enrichment backfill once. `musicbrainz_checked_at` moves with it for the reason
-- V054 gives.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

UPDATE artist
SET musicbrainz_enriched_at = NULL,
    musicbrainz_checked_at  = now()
WHERE musicbrainz_match = 'EXACT'
  AND artist_type IN ('GROUP', 'ORCHESTRA', 'CHOIR')
  AND wikidata_url IS NOT NULL
  AND description IS NULL;
