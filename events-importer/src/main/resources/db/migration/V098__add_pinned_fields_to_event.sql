-- The fields of an event that an operator fixed by hand, so the next import keeps them (ADR-042, #2590).
--
-- One entry per field, named as the admin API names it: an `EventRequest` property (`title`, `startTime`),
-- or `lineup`, `promoters` and `genres` for the join tables. `PUT /api/admin/events/{id}` adds every field it
-- changes, and `DELETE /api/admin/events/{id}/pins/{field}` removes one. Empty means the source owns every
-- field. No CHECK on the names: the importer ignores a name it does not know, and a new pinnable field
-- needs no migration.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE event
    ADD COLUMN pinned_fields TEXT[] NOT NULL DEFAULT '{}';
