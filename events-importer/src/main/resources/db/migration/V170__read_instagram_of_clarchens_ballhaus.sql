-- V170 — Clärchens Ballhaus on the Instagram drafts list (ADR-048).
--
-- V168 left it off the list because its site publishes a programme (ADR-043). The site's WAF answers
-- 403 to the importer's HTTP client, so its Instagram account is the only lawful route to its events.

UPDATE venue SET instagram_url = 'https://www.instagram.com/claerchens_ballhaus/' WHERE slug = 'clarchens-ballhaus' AND instagram_url IS NULL;

INSERT INTO instagram_venue (venue_id)
SELECT id
FROM venue
WHERE slug = 'clarchens-ballhaus'
ON CONFLICT DO NOTHING;
