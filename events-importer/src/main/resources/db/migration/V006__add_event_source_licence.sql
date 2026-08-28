-- What is known about our right to republish material from each source, per field (#283).
--
-- Two status columns rather than one, because the answers differ: a venue's own promotional prose
-- is usually its own, and its photographs are often an agency's. A single column would force the
-- stricter answer onto both.
--
-- Three evidence columns beside them, for the reason V005 gives about robots.txt: the questions a
-- reviewer asks are different. What the status is, when anyone last looked, and what they read.
-- A status with no page behind it cannot be checked by the next person.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

-- Nullable, no default, no backfill — V005's reasoning, unchanged. A default of 'UNCLEAR' would
-- claim 86 reviews that never happened, and the point of the columns is that an unreviewed source
-- is visible as unreviewed. Null and 'UNCLEAR' are different states and both display: the display
-- rule withholds only on 'PROHIBITED'.
ALTER TABLE event_source
    ADD COLUMN description_licence TEXT,
    ADD COLUMN image_licence       TEXT,
    ADD COLUMN licence_reviewed_at TIMESTAMPTZ,
    ADD COLUMN licence_source_url  TEXT,
    ADD COLUMN licence_note        TEXT;

-- Values are written through the admin API, which validates them against the SourceLicence enum.
-- This constraint is the second line: a hand-edited row cannot introduce a status the application
-- would then have to guess about. NULL stays allowed, and it means unreviewed.
ALTER TABLE event_source
    ADD CONSTRAINT event_source_description_licence_valid
        CHECK (description_licence IS NULL OR description_licence IN ('PERMITTED', 'PROHIBITED', 'UNCLEAR')),
    ADD CONSTRAINT event_source_image_licence_valid
        CHECK (image_licence IS NULL OR image_licence IN ('PERMITTED', 'PROHIBITED', 'UNCLEAR'));

-- Partial index over the only rows the read path filters on. Prohibitions are expected to be a
-- small minority, so indexing the whole column would be mostly dead entries.
CREATE INDEX idx_event_source_prohibited
    ON event_source (id)
    WHERE description_licence = 'PROHIBITED' OR image_licence = 'PROHIBITED';
