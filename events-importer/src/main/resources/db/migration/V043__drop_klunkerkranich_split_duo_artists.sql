-- Klunkerkranich bills a duo and a pair of separate acts the same way, and the lineup parser split
-- every `&`, so the duo `Überhaupt & Außerdem` was stored as two artists (#1760). The parser now
-- asks `isKnownSingleAct` first; this removes the two rows, because there is no re-seed on staging
-- or production.
--
-- Both rows hold that one night and nothing else, so neither is left linked to another event. The
-- FK cascade on event_artist removes the links, and the next import mints the duo under its own
-- name. `uberhaupt` also carries a MusicBrainz id for a different entity — the search holds a solo
-- `Überhaupt` beside the group that played — which is why the row is deleted and not renamed.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist
WHERE slug IN (
    'uberhaupt',
    'ausserdem'
);
