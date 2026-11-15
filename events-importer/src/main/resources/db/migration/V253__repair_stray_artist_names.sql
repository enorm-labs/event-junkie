-- Two stored artist rows that are no act name (#5136). Tresor billed `Ben Sims b2b Stevie Cox - All night long`, and the
-- row minted before `stripTrailingSeparator` kept the dash: `Stevie Cox` has the same slug, so the import reuses the row
-- and its name. Renate heads a floor run with a bare `DJS` line, which `isNonArtistName` now refuses. A past night is
-- never imported again, so the stored rows change here.
--
-- The rename is keyed on slug and the old name, so a row that is absent or was corrected by hand is left alone. It moves
-- `name_changed_at` (V062), so MusicBrainz and Discogs check the new name again. The drop deletes the row with its links
-- and its MusicBrainz match, and only where Renate alone bills it (V092).
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

UPDATE artist
SET name = 'Stevie Cox'
WHERE slug = 'stevie-cox'
  AND name = 'Stevie Cox -';

SELECT drop_artist_billed_only_by('djs', 'renate:');
