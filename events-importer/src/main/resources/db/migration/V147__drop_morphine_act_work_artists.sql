-- Three artist rows that are a work, or an act fused to its work, not acts (#3163). Morphine Raum's set line repeats a
-- `<act> – <work>` title: `Alister Spence – Within Without` was stored as one name, and `Antonio Raia - Σ [sigma] —
-- Berlin Series I` as three. The parser now bills the act alone. A past night is never imported again, so the rows keep
-- their links and OrphanArtistSweep never removes them.
--
-- The 2026-09-26 night bills Alister Spence in place of the fused row. The 2026-10-08 night keeps its Antonio Raia link.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

-- 1. The act, created only where the fused row exists and no row holds the slug yet.
INSERT INTO artist (name, slug)
SELECT 'Alister Spence', 'alister-spence'
WHERE EXISTS (SELECT 1 FROM artist WHERE slug = 'alister-spence-within-without')
  AND NOT EXISTS (SELECT 1 FROM artist WHERE slug = 'alister-spence');

-- 2. The night's link moves to the act, keeping its role, order and set times.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, act.id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM event_artist ea
JOIN event e ON e.id = ea.event_id
JOIN artist fused ON fused.id = ea.artist_id
JOIN artist act ON act.slug = 'alister-spence'
WHERE fused.slug = 'alister-spence-within-without'
  AND e.source_id = 'morphine:alister-spence-within-without-live-recording'
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 3. The fused row and the work's two rows, gone where only Morphine bills them.
SELECT drop_artist_billed_only_by('alister-spence-within-without', 'morphine:');
SELECT drop_artist_billed_only_by('sigma', 'morphine:');
SELECT drop_artist_billed_only_by('berlin-series-i', 'morphine:');
