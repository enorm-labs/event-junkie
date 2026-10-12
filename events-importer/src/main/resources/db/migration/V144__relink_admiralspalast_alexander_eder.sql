-- Admiralspalast billed `Alexander Eder Unplugged`, the act with the show's format word, on its 2026-10-09 night (#3150).
-- The parser now strips the word and bills `Alexander Eder`. A past night is never imported again, so this relinks it:
-- the act takes the old row's place, and the old row goes when no other source bills it. A database without the night is
-- left alone.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

INSERT INTO artist (name, slug)
SELECT 'Alexander Eder', 'alexander-eder'
WHERE EXISTS (
    SELECT 1 FROM event WHERE source_id = 'admiralspalast:alexander-eder-unplugged-die-akustik-tour-live-mit-band-2026-10-09-2000'
)
ON CONFLICT (slug) DO NOTHING;

INSERT INTO event_artist (event_id, artist_id, role, billing_order, title_derived)
SELECT e.id, a.id, 'HEADLINER', 0, TRUE
FROM event e
JOIN artist a ON a.slug = 'alexander-eder'
WHERE e.source_id = 'admiralspalast:alexander-eder-unplugged-die-akustik-tour-live-mit-band-2026-10-09-2000'
ON CONFLICT (event_id, artist_id) DO NOTHING;

SELECT drop_artist_billed_only_by('alexander-eder-unplugged', 'admiralspalast:');
