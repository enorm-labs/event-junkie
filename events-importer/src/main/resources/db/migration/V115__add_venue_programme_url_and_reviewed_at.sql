-- Venues we know but do not import get a row too (#2766), and those two columns are what such a row needs.
--
-- `programme_url` is where a visitor finds the programme of a venue without an importer: its own programme page
-- first, a Resident Advisor page as a plain link second, a ticket platform last. It is never fetched. An imported
-- venue leaves it NULL, because its events are on the site.
--
-- `reviewed_at` is when a person last confirmed the row's facts: address, coordinates, still open. NULL means
-- nobody did. Set through the admin API, never by an import, like `promoter.reviewed_at` (V028). #2812 builds the
-- check for closed venues on it.
--
-- "Imported" is not a column. A venue is imported while an `event_source` row points at it.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).
ALTER TABLE venue
    ADD COLUMN programme_url TEXT,
    ADD COLUMN reviewed_at   TIMESTAMPTZ,
    ADD CONSTRAINT venue_programme_url_http CHECK (programme_url ~ '^https?://');
