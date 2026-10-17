-- V168 — Instagram links for 21 venues, and 17 of them on the Instagram drafts list (ADR-048).
--
-- Fifteen handles come from the venue's own site, the press or a third-party page. Six were guessed
-- from the account name and confirmed by the operator. Business Discovery read every account.
-- 8MM, Clärchens Ballhaus, Pastiche and PKH Warehouse publish a programme an importer can read, so
-- they keep the link but stay off the list (ADR-043).

UPDATE venue SET instagram_url = 'https://www.instagram.com/8mmbar/' WHERE slug = '8mm-bar' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/ballhausberlin/' WHERE slug = 'ballhaus-berlin' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/birgit.club/' WHERE slug = 'birgit' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/bohnengold/' WHERE slug = 'bohnengold' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/bredouille.berlin/' WHERE slug = 'bredouille' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/bulbulberlin/' WHERE slug = 'bulbul-berlin' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/c115c115/' WHERE slug = 'c115' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/claerchens_ballhaus/' WHERE slug = 'clarchens-ballhaus' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/else_klub/' WHERE slug = 'else' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/found.berlin/' WHERE slug = 'found' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/interkosmos.berlin/' WHERE slug = 'interkosmos' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/ipse.ufer/' WHERE slug = 'ipse' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/lokschuppenberlin/' WHERE slug = 'lokschuppen-berlin' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/oel.garten/' WHERE slug = 'oelgarten' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/pasticheinternational/' WHERE slug = 'pastiche' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/pkhwarehouse/' WHERE slug = 'pkh-warehouse' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/prachtwerkberlin/' WHERE slug = 'prachtwerk' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/silverwings_club/' WHERE slug = 'silverwings' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/tipsy_bear_berlin/' WHERE slug = 'tipsy-bear' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/unkompress/' WHERE slug = 'unkompress' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/villaneukoelln/' WHERE slug = 'villa-neukolln' AND instagram_url IS NULL;

INSERT INTO instagram_venue (venue_id)
SELECT id
FROM venue
WHERE slug IN (
    'ballhaus-berlin', 'birgit', 'bohnengold', 'bredouille', 'bulbul-berlin', 'c115', 'else',
    'found', 'interkosmos', 'ipse', 'lokschuppen-berlin', 'oelgarten', 'prachtwerk', 'silverwings',
    'tipsy-bear', 'unkompress', 'villa-neukolln'
)
ON CONFLICT DO NOTHING;
