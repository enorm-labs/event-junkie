-- V265 — an act's Wikidata genres, and the origin they give an untagged event (#5222, ADR-060).
--
-- `artist.wikidata_genres` holds the item ids of Wikidata's P136 (genre) for an EXACT row, in statement order. NULL until
-- the artist lookup tick has read it, which is also the backfill's queue. Empty when the item names no genre. The ids,
-- not tag names, are stored, so a change to the importer's map applies at the next import without a new read.
--
-- `WIKIDATA` is the fourth origin of `event_genre_tag`. An event takes it only when no other origin gives a tag.
--
-- The partial index serves the backfill: the EXACT rows with a Wikidata link nobody has read.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE artist
    ADD COLUMN wikidata_genres TEXT[];

CREATE INDEX artist_wikidata_genres_unread_idx
    ON artist (id)
    WHERE musicbrainz_match = 'EXACT' AND wikidata_url IS NOT NULL AND wikidata_genres IS NULL;

-- The CHECK is widened: the line after the drop adds it back with every old value kept, in the same transaction.
-- squawk-ignore ban-drop-constraint
ALTER TABLE event_genre_tag DROP CONSTRAINT event_genre_tag_origin_check;
ALTER TABLE event_genre_tag ADD CONSTRAINT event_genre_tag_origin_check
    CHECK (origin IN ('SOURCE', 'ARTIST', 'DESCRIPTION', 'WIKIDATA'));
