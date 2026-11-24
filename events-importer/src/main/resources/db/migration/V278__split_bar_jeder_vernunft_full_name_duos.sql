-- Bar jeder Vernunft billed two full names joined by `&` as one act (#5468). The scraper now bills each name, but the
-- nights of 24 to 26 November 2026 (events 4759, 4760 and 4761) are past when this reaches a cluster or soon after, and
-- a past night is never imported again.
--
-- V215's shape: each act takes the fused row's place, keeping its role, stage and set times, and every later act moves
-- down one. Every step is keyed on the slug and the source, so a database without the row, or a same-slug row another
-- venue bills, is left alone, and a second run changes nothing.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE TEMPORARY TABLE replacement (
    old_slug      TEXT NOT NULL,
    source_prefix TEXT NOT NULL,
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    position      INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO replacement (old_slug, source_prefix, slug, name, position) VALUES
    ('rebecca-carrington-martina-colli', 'bar_jeder_vernunft:', 'rebecca-carrington', 'Rebecca Carrington', 0),
    ('rebecca-carrington-martina-colli', 'bar_jeder_vernunft:', 'martina-colli',      'Martina Colli',      1),
    ('pasquale-aleardi-die-phonauten',   'bar_jeder_vernunft:', 'pasquale-aleardi',   'Pasquale Aleardi',   0),
    ('pasquale-aleardi-die-phonauten',   'bar_jeder_vernunft:', 'die-phonauten',      'Die Phonauten',      1);

CREATE TEMPORARY TABLE fused_link ON COMMIT DROP AS
SELECT ea.*, a.slug AS old_slug, r.parts
FROM event_artist ea
JOIN artist a ON a.id = ea.artist_id
JOIN event e ON e.id = ea.event_id
JOIN (SELECT old_slug, source_prefix, count(*) AS parts FROM replacement GROUP BY old_slug, source_prefix) r
    ON r.old_slug = a.slug AND starts_with(e.source_id, r.source_prefix);

-- 1. An act without a row yet is created, only when there is a link to replace.
INSERT INTO artist (name, slug)
SELECT DISTINCT ON (r.slug) r.name, r.slug
FROM replacement r
WHERE EXISTS (SELECT 1 FROM fused_link f WHERE f.old_slug = r.old_slug)
  AND NOT EXISTS (SELECT 1 FROM artist a WHERE a.slug = r.slug)
ORDER BY r.slug;

-- 2. Make room: a link billed after the fused row moves down one for each extra act.
UPDATE event_artist ea
SET billing_order = ea.billing_order + (
    SELECT sum(g.parts - 1)
    FROM fused_link g
    WHERE g.event_id = ea.event_id
      AND g.billing_order < ea.billing_order
)
WHERE EXISTS (
    SELECT 1
    FROM fused_link g
    WHERE g.event_id = ea.event_id
      AND g.billing_order < ea.billing_order
      AND g.parts > 1
);

-- 3. The acts take the fused row's place, keeping its role, stage, title_derived flag and set times.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived, set_start, set_end)
SELECT f.event_id, a.id, f.role, f.billing_order + r.position, f.stage, f.title_derived, f.set_start, f.set_end
FROM fused_link f
JOIN replacement r ON r.old_slug = f.old_slug
JOIN artist a ON a.slug = r.slug
ON CONFLICT (event_id, artist_id) DO NOTHING;

-- 4. The fused link goes, then the fused row, unless another venue still bills it.
DELETE FROM event_artist ea
USING fused_link f
WHERE ea.event_id = f.event_id
  AND ea.artist_id = f.artist_id;

SELECT drop_artist_billed_only_by(old_slug, source_prefix)
FROM (SELECT DISTINCT old_slug, source_prefix FROM replacement) old;
