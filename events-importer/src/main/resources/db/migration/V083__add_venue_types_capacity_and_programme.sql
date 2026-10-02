-- What kind of place a venue is, how many people it holds, and what it programmes (#327, #369).
--
-- `venue_types` and `capacity` are curated by hand, once per venue; V084 fills them. A venue can be
-- more than one type, so the column is an array: SO36 is a live venue and a club.
--
-- `programme_families` and `programme_event_types` are derived from the venue's own events by
-- VenueProgrammeService, after each import and nightly. They are never written by hand, so they
-- follow the programme when it changes. Both start empty and fill on the first pass.
--
-- The array values are slugs from closed vocabularies in events-core: VenueType, GenreFamily,
-- EventType. No CHECK on them, as V022 argues for `genre_tag.family`: the vocabulary is code.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE venue
    ADD COLUMN venue_types TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN capacity INTEGER CHECK (capacity > 0),
    ADD COLUMN programme_families TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN programme_event_types TEXT[] NOT NULL DEFAULT '{}';

-- The public venue list filters with `&&` (overlap) on each array.
CREATE INDEX idx_venue_venue_types ON venue USING GIN (venue_types);
CREATE INDEX idx_venue_programme_families ON venue USING GIN (programme_families);
CREATE INDEX idx_venue_programme_event_types ON venue USING GIN (programme_event_types);
