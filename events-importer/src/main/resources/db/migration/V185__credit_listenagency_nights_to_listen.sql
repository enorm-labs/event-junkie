-- Two nights credit "listenagency", the name Listen Collective books under (#3670): Mar Malade at Columbia
-- ("präsentiert von listenagency & untoldency") and Martin Kohlstedt at Admiralspalast ("Veranstalter:
-- listenagency"). The old correction stored both as Friendly Reminder. A past night is never imported again,
-- so their link moves to `listen` here. Keyed on slug and a no-op where a row is absent.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` before
-- running this (ADR-004).

CREATE TEMPORARY TABLE listen_nights (slug TEXT NOT NULL) ON COMMIT DROP;

INSERT INTO listen_nights (slug) VALUES
    ('2026-10-14-columbia-theater-mar-malade'),
    ('2026-11-30-admiralspalast-martin-kohlstedt');

INSERT INTO event_promoter (event_id, promoter_id)
SELECT e.id, l.id
FROM event e
JOIN listen_nights n ON n.slug = e.slug
JOIN promoter l ON l.slug = 'listen'
JOIN event_promoter ep ON ep.event_id = e.id
JOIN promoter fr ON fr.id = ep.promoter_id AND fr.slug = 'friendly-reminder'
ON CONFLICT (event_id, promoter_id) DO NOTHING;

DELETE FROM event_promoter ep
USING event e, listen_nights n, promoter fr
WHERE e.slug = n.slug
  AND ep.event_id = e.id
  AND fr.id = ep.promoter_id
  AND fr.slug = 'friendly-reminder'
  AND EXISTS (SELECT 1 FROM promoter l WHERE l.slug = 'listen');
