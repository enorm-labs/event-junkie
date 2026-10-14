-- V159 — the slugs an artist had before a rename (#3289).
--
-- A rename through the admin API changes the artist's slug, and the import finds an artist by the
-- slug of the scraped name. Without the old slug the next import created the old name again and
-- billed it in place of the renamed artist. The import looks a scraped slug up here after `artist`,
-- and the admin API refuses a new artist or a rename whose slug is another artist's alias.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TABLE artist_slug_alias
(
    slug       TEXT        PRIMARY KEY,
    artist_id  BIGINT      NOT NULL REFERENCES artist (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_artist_slug_alias_artist_id ON artist_slug_alias (artist_id);
