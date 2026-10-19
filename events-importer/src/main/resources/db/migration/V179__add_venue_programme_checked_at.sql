-- When a person last looked at the programme of a venue we do not import, to enter its new events by hand (#2993).
-- NULL means nobody did. The admin "Manual venues" page lists the open venues without an event source, the one never
-- checked or checked longest ago first. Set through the admin API, never by an import.
--
-- Not `reviewed_at`: that one records the review of the venue's facts, address, coordinates and opening (V115).
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).
ALTER TABLE venue
    ADD COLUMN programme_checked_at TIMESTAMPTZ;
