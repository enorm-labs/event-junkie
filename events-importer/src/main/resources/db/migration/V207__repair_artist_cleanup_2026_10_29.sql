-- The /fix-events run of 2026-10-29. Two productions minted as acts from a show title whose tail names the format, and a
-- Soulcat DJ whose alias keeps its `DJ`. The parsers now get all three right, but a past night is never imported again, so
-- its links move here.
--
-- Keyed on slug and on source, so a same-slug row another venue bills is left alone. Unqualified table names, deliberately:
-- Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

-- 1. Productions, not acts: `Die Bettwurst – Das Musical!`, `Der Nussknacker – Grand Classic Ballet mit Orchester`.
-- Their own venue's links go, then the row, unless another venue still bills it.
CREATE TEMPORARY TABLE production (
    slug          TEXT NOT NULL,
    source_prefix TEXT NOT NULL
) ON COMMIT DROP;

INSERT INTO production (slug, source_prefix) VALUES
    ('die-bettwurst',   'bar_jeder_vernunft:'),
    ('der-nussknacker', 'uber_eats_music_hall:');

DELETE FROM event_artist ea
USING artist a, event e, production p
WHERE a.id = ea.artist_id
  AND a.slug = p.slug
  AND e.id = ea.event_id
  AND starts_with(e.source_id, p.source_prefix);

SELECT drop_artist_billed_only_by(slug, source_prefix) FROM production;

-- 2. `Kassettendeck` becomes `DJ Kassettendeck`, confirmed by the owner. Renamed in place, keeping its enrichment, when only
-- Soulcat bills it and no `dj-kassettendeck` row exists yet. Otherwise its Soulcat links move onto that row.
UPDATE artist a
SET name = 'DJ Kassettendeck',
    slug = 'dj-kassettendeck'
WHERE a.slug = 'kassettendeck'
  AND NOT EXISTS (SELECT 1 FROM artist s WHERE s.slug = 'dj-kassettendeck')
  AND NOT EXISTS (
      SELECT 1
      FROM event_artist ea
      JOIN event e ON e.id = ea.event_id
      WHERE ea.artist_id = a.id
        AND NOT starts_with(e.source_id, 'soulcat:')
  );

INSERT INTO artist (name, slug)
SELECT 'DJ Kassettendeck', 'dj-kassettendeck'
WHERE NOT EXISTS (SELECT 1 FROM artist WHERE slug = 'dj-kassettendeck')
  AND EXISTS (
      SELECT 1
      FROM event_artist ea
      JOIN artist a ON a.id = ea.artist_id
      JOIN event e ON e.id = ea.event_id
      WHERE a.slug = 'kassettendeck'
        AND starts_with(e.source_id, 'soulcat:')
  );

INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT ea.event_id, s.id, ea.role, ea.billing_order, ea.stage, ea.title_derived, ea.set_start, ea.set_end
FROM event_artist ea
JOIN artist l ON l.id = ea.artist_id AND l.slug = 'kassettendeck'
JOIN event e ON e.id = ea.event_id AND starts_with(e.source_id, 'soulcat:')
JOIN artist s ON s.slug = 'dj-kassettendeck'
ON CONFLICT (event_id, artist_id) DO NOTHING;

DELETE FROM event_artist ea
USING artist l, event e
WHERE l.id = ea.artist_id
  AND l.slug = 'kassettendeck'
  AND e.id = ea.event_id
  AND starts_with(e.source_id, 'soulcat:');

SELECT drop_artist_billed_only_by('kassettendeck', 'soulcat:');
