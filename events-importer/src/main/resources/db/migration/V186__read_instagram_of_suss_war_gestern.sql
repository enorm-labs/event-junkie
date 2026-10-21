-- V186 — Süss war gestern's Instagram link, and the bar on the Instagram drafts list (ADR-048).
--
-- The bar has no site of its own. Its account, confirmed by the operator, posts each week's DJ line-up
-- with dates. The update keeps a link someone already set by hand.

UPDATE venue SET instagram_url = 'https://www.instagram.com/suess.wargestern/' WHERE slug = 'suss-war-gestern' AND instagram_url IS NULL;

INSERT INTO instagram_venue (venue_id)
SELECT id
FROM venue
WHERE slug = 'suss-war-gestern'
ON CONFLICT DO NOTHING;
