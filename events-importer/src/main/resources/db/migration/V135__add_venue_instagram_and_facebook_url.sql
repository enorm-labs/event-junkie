-- A venue's Instagram and Facebook pages, linked beside its website (#2839). Many small venues post their nights
-- only there, so for a venue without a site, or with a stale one, the account is where a visitor finds out
-- whether it is open tonight.
--
-- Plain outbound links, like `website_url` and `programme_url`: entered by a person, never fetched, nothing
-- embedded. Meta's terms forbid automated access (#356). The admin API also pins each column to its own host,
-- so the two cannot be swapped; the database checks only the scheme, like `programme_url` (V115).
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).
ALTER TABLE venue
    ADD COLUMN instagram_url TEXT,
    ADD COLUMN facebook_url  TEXT,
    ADD CONSTRAINT venue_instagram_url_http CHECK (instagram_url ~ '^https?://'),
    ADD CONSTRAINT venue_facebook_url_http CHECK (facebook_url ~ '^https?://');
