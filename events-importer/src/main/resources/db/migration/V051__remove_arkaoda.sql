-- arkaoda Berlin closed on 2026-08-30 (#1788). Its programme page is not broken and its scraper was
-- not failing: the page is the bare template because there is nothing left to announce. The venue
-- said so itself in the only row either cluster holds, `2026-08-30-arkaoda-the-end`, whose
-- description is the club's own closing note.
--
-- The decision recorded on the issue is to remove the venue rather than keep it on the site with a
-- present-tense description of a club that no longer exists. `Venue` has no `closed_at`, so there is
-- no way to say it; a venue page serving one past event explains nothing to a visitor. The scraper,
-- the `EventSource` entry and the seed block go with it in the same change.
--
-- Order matters. `event.event_source_id` is `ON DELETE SET NULL` and `event.venue_id` is a plain
-- reference, so the events go first, then the source row, then the venue. `event_artist`,
-- `event_genre_tag` and `event_promoter` follow the event by cascade. The one event holds no
-- artist, promoter or genre link, so this orphans nothing.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM event
WHERE venue_id IN (SELECT id FROM venue WHERE slug = 'arkaoda');

DELETE FROM event_source
WHERE venue_id IN (SELECT id FROM venue WHERE slug = 'arkaoda');

DELETE FROM venue
WHERE slug = 'arkaoda';
