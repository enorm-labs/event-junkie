-- Astra's secret-lineup night is titled after the series that promotes it, so the
-- title-as-headliner default minted `Unreleased Berlin` as an act beside its own promoter credit
-- (#1772). The sync is fixed; this removes the row, because there is no re-seed on staging or
-- production.
--
-- The row holds that one night and carries no MusicBrainz id. The FK cascade on event_artist
-- removes the link, and the promoter of the same name is a separate table and is untouched.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist
WHERE slug = 'unreleased-berlin';
