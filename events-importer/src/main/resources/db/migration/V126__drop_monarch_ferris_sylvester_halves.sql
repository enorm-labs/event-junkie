-- Monarch bills the duo `FERRIS & SYLVESTER`, and the title split stored it as two artists, `Ferris` and `Sylvester`
-- (#2952). The duo is now a known single act. A past night is never imported again, so its halves would keep their link.

SELECT drop_artist_billed_only_by('ferris', 'monarch:');
SELECT drop_artist_billed_only_by('sylvester', 'monarch:');
