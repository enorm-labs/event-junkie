-- A performer's occupation may type a night the venue did not type (#2314, ADR-039).
--
-- `artist.comedian` is what Wikidata's P106 (occupation) says about an EXACT row: true when it names
-- a comedian, a stand-up comedian or a cabaret performer, false when it names none of them. NULL until
-- the artist lookup tick has read it, which is also the backfill's queue. No other occupation is stored.
--
-- `event.type_is_fallback` is true when the scraper's type is its own default, because the venue gave
-- no cue: no category, no format line, no title keyword. Only such a row may be retyped by its
-- headliner. Every existing row starts false, so a source is retyped only after its next import.
--
-- The partial index serves the backfill: the EXACT rows with a Wikidata link nobody has read.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE artist
    ADD COLUMN comedian BOOLEAN;

ALTER TABLE event
    ADD COLUMN type_is_fallback BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX artist_comedian_unread_idx
    ON artist (id)
    WHERE musicbrainz_match = 'EXACT' AND wikidata_url IS NOT NULL AND comedian IS NULL;
