-- V224 and V244 split a stored act in two and inserted the second act at the fused row's order plus one, with no step that
-- moved the later acts down (#5137). That act then shares its order with the act billed after the fused row: Golden Gate's
-- event 11839 has Sharhok Dini and Javier Anxiety both at 3. The import renumbers a lineup it imports again, so only a
-- past event keeps the duplicate.
--
-- Each event where an order repeats is renumbered 0..n-1. A tie goes to the newer row, which is the act the split
-- inserted, so it stays next to its partner. An event with no repeated order is left alone. No constraint covers
-- `(event_id, billing_order)`, so the update runs in place.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

WITH duplicated AS (
    SELECT DISTINCT event_id
    FROM event_artist
    GROUP BY event_id, billing_order
    HAVING count(*) > 1
),
ranked AS (
    SELECT ea.id, (row_number() OVER (PARTITION BY ea.event_id ORDER BY ea.billing_order, ea.id DESC) - 1)::INTEGER AS position
    FROM event_artist ea
    JOIN duplicated d ON d.event_id = ea.event_id
)
UPDATE event_artist ea
SET billing_order = r.position
FROM ranked r
WHERE ea.id = r.id
  AND ea.billing_order <> r.position;
