-- V266 — an artist's Wikidata occupations and genres are read again after `app.wikimedia.reread-after` (#5253).
--
-- `artist.wikidata_read_at` is when the artist lookup tick last read the row's Wikidata item. The genre read sets it, because
-- it runs last on the tick: a stamp from the occupation read would hide the row from the genre read's queue. NULL for every
-- existing row, so each one is read again once, after the rows nobody has read.
--
-- One partial index replaces those of V094 and V265. It serves both queues: the unread rows, then the oldest read.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE artist
    ADD COLUMN wikidata_read_at TIMESTAMPTZ;

-- DROP INDEX CONCURRENTLY cannot run inside Flyway's transaction, and the artist table is small, so the lock is brief.
-- squawk-ignore require-concurrent-index-deletion
DROP INDEX artist_comedian_unread_idx;
-- squawk-ignore require-concurrent-index-deletion
DROP INDEX artist_wikidata_genres_unread_idx;

CREATE INDEX artist_wikidata_read_idx
    ON artist (wikidata_read_at NULLS FIRST, id)
    WHERE musicbrainz_match = 'EXACT' AND wikidata_url IS NOT NULL;
