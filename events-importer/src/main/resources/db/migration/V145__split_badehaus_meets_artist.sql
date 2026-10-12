-- Badehaus billed `Roots Daughters meets La Comadre Soundsystem` as one act (#3159). The title splitter now reads a
-- space-padded `meets` as a co-bill. That night is past, so no import repairs it: the two acts take the fused row's
-- place at its role and billing order, and the fused row goes.
--
-- Keyed on the fused row's slug and on Badehaus nights only, so a database without the row is left alone.
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE split_act (
    slug     TEXT NOT NULL,
    name     TEXT NOT NULL,
    position INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO split_act (slug, name, position) VALUES
    ('roots-daughters',        'Roots Daughters',        0),
    ('la-comadre-soundsystem', 'La Comadre Soundsystem', 1);

CREATE TEMPORARY TABLE fused_link ON COMMIT DROP AS
SELECT ea.*
FROM event_artist ea
JOIN artist a ON a.id = ea.artist_id
JOIN event e ON e.id = ea.event_id
WHERE a.slug = 'roots-daughters-meets-la-comadre-soundsystem'
  AND starts_with(e.source_id, 'badehaus:');

-- 1. An act without a row yet is created, only when there is a link to replace.
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

-- 3. The two acts take the fused row's place, keeping its role, stage and title_derived flag.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT f.event_id, a.id, f.role, f.billing_order + s.position, f.stage, f.title_derived, f.set_start, f.set_end
FROM fused_link f
CROSS JOIN split_act s
JOIN artist a ON a.slug = s.slug
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 4. The fused row, gone with its links, unless another venue bills it.
SELECT drop_artist_billed_only_by('roots-daughters-meets-la-comadre-soundsystem', 'badehaus:');
