-- Every EXACT Discogs verdict goes back to UNCHECKED, so the lookup asks again under rule 5 (#2054).
--
-- The hand review on #2026 found 8 homonyms in 40 EXACT links. Rule 5 keeps an EXACT match only
-- when the Discogs artist released something recently. The verdicts reached before it are asked
-- again: the backfill reads UNCHECKED rows, and the partial index of V061 serves it.
--
-- The link goes only where the old verdict wrote it: the page of the stored Discogs id. A link that
-- MusicBrainz or a venue gave stays, the rule `storeDiscogsVerdict` follows. The CASE reads the row
-- as it was before this UPDATE, so clearing the id in the same statement does not hide it.
--
-- Production has no Discogs verdict yet (the lookup is off there, #2050), so this changes no row
-- on it.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

UPDATE artist
SET discogs_match      = 'UNCHECKED',
    discogs_id         = NULL,
    discogs_checked_at = NULL,
    discogs_url        = CASE
                             WHEN discogs_url LIKE 'https://www.discogs.com/artist/' || discogs_id || '%' THEN NULL
                             ELSE discogs_url
                         END
WHERE discogs_match = 'EXACT';
