-- Whether an operator fixed an artist's name by hand, so the MusicBrainz enrichment keeps it (ADR-042, #2636).
--
-- The enrichment writes MusicBrainz's letter case on an EXACT row (#2317). It fills every other column only
-- when it is empty, so the name is the one column it overwrites, and one flag is enough. `PUT
-- /api/admin/artists/{id}` sets it when the name changes, and `DELETE /api/admin/artists/{id}/pins/name`
-- clears it. Existing rows start unpinned: no admin edit before this change was recorded.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE artist
    ADD COLUMN name_pinned BOOLEAN NOT NULL DEFAULT false;
