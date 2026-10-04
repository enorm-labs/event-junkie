-- What the import's sync gate kept out of an event, so the data-quality worklist can list it (#320).
--
-- The value stays out of the event, as before. This table records that it was there: an artist name
-- that slugs to nothing, a name the non-artist vocabulary refuses, a title-derived name the event also
-- credits as its promoter, a genre that repeats the title, a genre word that names no genre.
-- Before, each was a log line or nothing, and a steward never saw it.
--
-- The smallest shape that does that: no surrogate id, the three columns are the key. Each import
-- replaces the rows of the events it touched, so a row means "the latest import of this event saw
-- this". ON DELETE CASCADE, because a flag about a deleted event is about nothing.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TABLE event_quality_flag
(
    event_id BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    kind     TEXT   NOT NULL CHECK (kind IN ('SLUGLESS_ARTIST', 'NON_ARTIST_NAME', 'HELD_BACK_PROMOTER_NAME',
                                             'GENRE_EQUALS_TITLE', 'NON_GENRE_TOKEN')),
    value    TEXT   NOT NULL,
    PRIMARY KEY (event_id, kind, value)
);
