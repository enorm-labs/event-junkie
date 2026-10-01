-- The room a whole event is in, in the venue's own words (#316).
--
-- A venue with more than one room can run two shows on one night, so the venue alone does not tell a visitor where to
-- go. `event_artist.stage` cannot carry it: an exhibition or a lineup-less night has no act to hang a room on. The
-- two never overlap — `room` for an event in one room, `stage` for a lineup split across rooms. Null when the venue
-- names no room.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).
ALTER TABLE event
    ADD COLUMN room TEXT;
