-- A-Trane billed the guest line `HEUTE MIT: SEBASTIAN MERK-JOHN SCHRÖDER` as one act (#4374). The parser now splits a
-- bare hyphen with two words on each side. The 2026-11-02 night is past, so no import repairs it: the two guests take
-- the fused row's place at its role and billing order, and the fused row goes.
--
-- Keyed on the fused row's slug and on A-Trane nights only. A re-import that billed the guests first leaves no fused
-- link, so the split does nothing and only an unbilled fused row goes. A second run changes nothing.
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE split_act (
    slug     TEXT NOT NULL,
    name     TEXT NOT NULL,
    position INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO split_act (slug, name, position) VALUES
    ('sebastian-merk', 'Sebastian Merk', 0),
    ('john-schroder',  'John Schröder',  1);

CREATE TEMPORARY TABLE fused_link ON COMMIT DROP AS
SELECT ea.*
FROM event_artist ea
JOIN artist a ON a.id = ea.artist_id
JOIN event e ON e.id = ea.event_id
WHERE a.slug = 'sebastian-merk-john-schroder'
  AND starts_with(e.source_id, 'a_trane:');

-- 1. A guest without a row yet is created, only when there is a link to replace.
INSERT INTO artist (name, slug)
SELECT s.name, s.slug
FROM split_act s
WHERE EXISTS (SELECT 1 FROM fused_link)
  AND NOT EXISTS (SELECT 1 FROM artist a WHERE a.slug = s.slug);

-- 2. Make room: a link billed after the fused row moves down by one.
UPDATE event_artist ea
SET billing_order = ea.billing_order + 1
FROM fused_link f
WHERE ea.event_id = f.event_id
  AND ea.billing_order > f.billing_order;

-- 3. The two guests take the fused row's place, keeping its role, stage, title_derived flag and set times.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT f.event_id, a.id, f.role, f.billing_order + s.position, f.stage, f.title_derived, f.set_start, f.set_end
FROM fused_link f
CROSS JOIN split_act s
JOIN artist a ON a.slug = s.slug
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 4. The fused row, gone with its links, unless another venue bills it.
SELECT drop_artist_billed_only_by('sebastian-merk-john-schroder', 'a_trane:');
