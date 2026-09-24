-- An EXACT ensemble's description from its Wikipedia lead (ADR-031, step C+, #1837).
--
-- `description_language` says which wiki the text is from, so the page can declare it (ADR-026).
-- Only one text is stored: the dewiki and enwiki leads are two articles, and one credit links one.
--
-- The credit is all or none, as V020 made the image credit, and set only beside a text. A text a
-- venue or a person wrote carries no credit, so the text alone may stand.
--
-- The UPDATE puts the ensembles already read back into the enrichment backfill, which fills only
-- empty columns. `musicbrainz_checked_at` moves with it: the updated_at trigger fires on this UPDATE,
-- and a row updated after its verdict is queued for a fresh lookup (see ArtistEnrichmentStore).
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE artist
    ADD COLUMN description_language    TEXT,
    ADD COLUMN description_attribution TEXT,
    ADD COLUMN description_licence_id  TEXT,
    ADD COLUMN description_source_url  TEXT;

ALTER TABLE artist
    ADD CONSTRAINT artist_description_language_valid
        CHECK (description_language IS NULL OR description_language IN ('de', 'en')),
    ADD CONSTRAINT artist_description_attributed
        CHECK (
            (description_attribution IS NULL AND description_licence_id IS NULL AND description_source_url IS NULL)
            OR (description IS NOT NULL
                AND description_attribution IS NOT NULL AND description_licence_id IS NOT NULL AND description_source_url IS NOT NULL)
        );

UPDATE artist
SET musicbrainz_enriched_at = NULL,
    musicbrainz_checked_at  = now()
WHERE musicbrainz_match = 'EXACT'
  AND artist_type IN ('GROUP', 'ORCHESTRA', 'CHOIR')
  AND wikidata_url IS NOT NULL
  AND description IS NULL;
