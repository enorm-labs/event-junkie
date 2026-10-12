-- Every path on rso.berlin answers 404, and the club names no other site of its own (#3141). The venue page linked
-- visitors to that 404, so the website goes; the RA programme link stays. Nothing re-seeds the clusters, so the change
-- is a migration. It does nothing where the venue is missing.
UPDATE venue
SET website_url = NULL
WHERE slug = 'rso-berlin';
