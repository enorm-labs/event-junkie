-- An ensemble's second Wikipedia lead, from the other wiki's own article (#1849).
--
-- V054 stored one lead, because one credit links one article. The second lead gets its own credit,
-- all or none as V054's, and the language pair follows venue and promoter (V019, V024).
--
-- An alt text stands only beside a Wikipedia primary in the other language. A venue's or a person's
-- text and a Wikipedia lead would not say the same thing, so the pair is a constraint, not a habit.
--
-- The UPDATE puts the ensembles V054 filled back into the enrichment backfill once, to read the
-- second lead. `musicbrainz_checked_at` moves with it for the reason V054 gives.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE artist
    ADD COLUMN description_alt             TEXT,
    ADD COLUMN description_alt_language    TEXT,
    ADD COLUMN description_alt_attribution TEXT,
    ADD COLUMN description_alt_licence_id  TEXT,
    ADD COLUMN description_alt_source_url  TEXT;

ALTER TABLE artist
    ADD CONSTRAINT artist_description_alt_language_valid
        CHECK (description_alt_language IS NULL OR description_alt_language IN ('de', 'en')),
    ADD CONSTRAINT artist_description_alt_complete
        CHECK (
            (description_alt IS NULL AND description_alt_language IS NULL)
            OR (description_alt IS NOT NULL AND description_alt_language IS NOT NULL)
        ),
    ADD CONSTRAINT artist_description_alt_attributed
        CHECK (
            (description_alt_attribution IS NULL AND description_alt_licence_id IS NULL AND description_alt_source_url IS NULL)
            OR (description_alt IS NOT NULL
                AND description_alt_attribution IS NOT NULL AND description_alt_licence_id IS NOT NULL AND description_alt_source_url IS NOT NULL)
        ),
    ADD CONSTRAINT artist_description_alt_beside_wikipedia
        CHECK (
            description_alt IS NULL
            OR (description_attribution IS NOT NULL
                AND description_language IS NOT NULL
                AND description_alt_language <> description_language)
        );

UPDATE artist
SET musicbrainz_enriched_at = NULL,
    musicbrainz_checked_at  = now()
WHERE musicbrainz_match = 'EXACT'
  AND artist_type IN ('GROUP', 'ORCHESTRA', 'CHOIR')
  AND wikidata_url IS NOT NULL
  AND description_attribution IS NOT NULL;
