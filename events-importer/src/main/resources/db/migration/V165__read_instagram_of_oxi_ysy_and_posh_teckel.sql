-- V165 — three more venues for the Instagram drafts (ADR-048).
--
-- Each handle comes from the venue's own site, and Business Discovery read each
-- account. OXI and YSY had no Instagram link stored. Posh Teckel had one.

UPDATE venue SET instagram_url = 'https://www.instagram.com/oxi_club/' WHERE slug = 'oxi' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/ysy.berlin/' WHERE slug = 'ysy' AND instagram_url IS NULL;

INSERT INTO instagram_venue (venue_id)
SELECT id
FROM venue
WHERE slug IN ('oxi', 'ysy', 'posh-teckel')
ON CONFLICT DO NOTHING;
