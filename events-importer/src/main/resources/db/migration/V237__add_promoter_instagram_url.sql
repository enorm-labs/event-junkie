-- A promoter's Instagram profile, linked beside its website, as V135 does for a venue. Many promoters
-- announce their shows only there, and some have no site at all.
--
-- A plain outbound link: entered by a person, never fetched, nothing embedded (#356). The admin API pins
-- the column to its host. The database checks only the scheme, like `venue.instagram_url`.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).
ALTER TABLE promoter
    ADD COLUMN instagram_url TEXT,
    ADD CONSTRAINT promoter_instagram_url_http CHECK (instagram_url ~ '^https?://');
