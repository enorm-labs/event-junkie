-- V219 — Sensorium's Instagram link, and the club on the Instagram drafts list (ADR-048).
--
-- The club's own site has no programme, and RA is off limits. Its account, named by the operator, posts the nights.
-- The update keeps a link someone already set by hand.

UPDATE venue SET instagram_url = 'https://www.instagram.com/sensorium.berlin/' WHERE slug = 'sensorium' AND instagram_url IS NULL;

INSERT INTO instagram_venue (venue_id)
SELECT id
FROM venue
WHERE slug = 'sensorium'
ON CONFLICT DO NOTHING;
