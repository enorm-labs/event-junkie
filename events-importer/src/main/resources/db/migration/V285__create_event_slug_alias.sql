-- V285 — the slugs an event had before its title changed (#5560).
--
-- An event's slug is its date, venue and stored title, so a retitle by an import or an admin edit
-- moves its URL, and a shared link to the old one answered 404. The importer and the admin edit
-- keep the old slug here, and the BFF answers a slug that is no event's through it. A live slug
-- wins: the writer deletes an alias when an event takes its slug, and the BFF looks here second.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TABLE event_slug_alias
(
    slug       TEXT        PRIMARY KEY,
    event_id   BIGINT      NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_event_slug_alias_event_id ON event_slug_alias (event_id);

-- The one retitle measured before the table existed: a hand fix renamed event 2962 on production.
-- Older slugs were never stored, so there is nothing else to backfill. Inserts nothing where the
-- event does not exist.
INSERT INTO event_slug_alias (slug, event_id)
SELECT '2026-11-25-uber-arena-wird', id
FROM event
WHERE slug = '2026-11-25-uber-arena-konstantinos-argiros';
