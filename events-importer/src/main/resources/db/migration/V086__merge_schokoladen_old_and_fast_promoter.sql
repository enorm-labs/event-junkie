-- Schokoladen's "old & fast prsnts:" credits one presenter, and the span split minted two rows,
-- `old` and `fast` (#2415). The scraper now keeps the name whole. The night is past before this
-- runs, so no import relinks it; this moves its links onto `old & fast` and deletes the halves.
--
-- Only links from Schokoladen events move. A half that another venue still credits keeps its row
-- and those links. A no-op where neither row exists.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

INSERT INTO promoter (name, slug)
SELECT 'old & fast', 'old-fast'
WHERE EXISTS (
    SELECT 1
    FROM promoter l
    JOIN event_promoter ep ON ep.promoter_id = l.id
    JOIN event e ON e.id = ep.event_id
    WHERE l.slug IN ('old', 'fast')
      AND e.source_id LIKE 'schokoladen:%'
)
ON CONFLICT (slug) DO NOTHING;

-- Inserted, not moved, as in V066: both halves link the same night, and moving both links onto
-- one row trips UNIQUE (event_id, promoter_id).
INSERT INTO event_promoter (event_id, promoter_id)
SELECT DISTINCT ep.event_id, s.id
FROM promoter l
JOIN event_promoter ep ON ep.promoter_id = l.id
JOIN event e ON e.id = ep.event_id
JOIN promoter s ON s.slug = 'old-fast'
WHERE l.slug IN ('old', 'fast')
  AND e.source_id LIKE 'schokoladen:%'
ON CONFLICT (event_id, promoter_id) DO NOTHING;

DELETE FROM event_promoter ep
USING promoter l, event e
WHERE ep.promoter_id = l.id
  AND e.id = ep.event_id
  AND l.slug IN ('old', 'fast')
  AND e.source_id LIKE 'schokoladen:%';

DELETE FROM promoter l
WHERE l.slug IN ('old', 'fast')
  AND NOT EXISTS (SELECT 1 FROM event_promoter ep WHERE ep.promoter_id = l.id);
