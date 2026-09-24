-- One artist row is still a whole bill written as one name: `Elshan Ghasimi, Carla Boregas & Joss
-- Turnbull`, billed by a Madame Claude night on 2026-08-31 (#1789). A single top-level comma is not
-- read as a separator and must not be — `Hey, Nothing`, `Kitty, Daisy & Lewis`, `Wracaj, bociemno`
-- and `Yes, I'm Very Tired Now` are real acts with one — so no parser change reaches this row.
--
-- The event is in the past and is never re-imported, so nothing will replace the link, and
-- OrphanArtistSweep only takes a row no event bills. A migration is the only thing that removes it,
-- which is why `V050` removed its two stale rows the same way. The night keeps its three other acts.
--
-- The FK cascade on event_artist removes the link.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist
WHERE slug = 'elshan-ghasimi-carla-boregas-joss-turnbull';
