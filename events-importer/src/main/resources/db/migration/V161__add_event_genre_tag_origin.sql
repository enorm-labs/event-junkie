-- V161 — where an event's genre tag comes from (#3339, ADR-047).
--
-- `SOURCE` is a tag the event's source published, and every existing row is one. `ARTIST` comes from
-- the source tags of the billed acts' other events. `DESCRIPTION` comes from genre words in the
-- event's description. The importer derives a tag only for an event with no source tag.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE event_genre_tag
    ADD COLUMN origin TEXT NOT NULL DEFAULT 'SOURCE'
        CHECK (origin IN ('SOURCE', 'ARTIST', 'DESCRIPTION'));
