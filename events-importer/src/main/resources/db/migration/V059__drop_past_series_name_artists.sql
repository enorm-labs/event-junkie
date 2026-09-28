-- Three artist rows are the name of a series or a festival, minted as its own act before #1952
-- stopped the importer doing it: Cassiopeia's `Thrash Talk Vol 5`, Festsaal's `In The Mountains`
-- and Badehaus's `Rockstar Girlfriends` (#1978). Each belongs to one event on 2026-09-26. An
-- import rewrites upcoming rows only and there is no re-seed on staging or production, so the past
-- links keep the rows alive and OrphanArtistSweep never sees them orphaned.
--
-- Named by slug, as V050 did. Measured read-only on production on 2026-09-28: these are the only
-- rows whose name ends in a numbered volume or matches the three new denylist entries. A slug a
-- cluster does not have is a no-op.
--
-- The FK cascade on event_artist removes the links.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist
WHERE slug IN (
    'thrash-talk-vol-5',
    'in-the-mountains',
    'rockstar-girlfriends'
);
