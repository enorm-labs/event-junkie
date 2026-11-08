-- V236 — Instagram links for 12 more venues, confirmed by the operator.
--
-- The update keeps a link someone already set by hand.

UPDATE venue
SET instagram_url = link.url
FROM (VALUES
    ('amt', 'https://www.instagram.com/club.amt.berlin/'),
    ('badenscher-hof', 'https://www.instagram.com/badenscher.hof.jazzclub/'),
    ('colosseum', 'https://www.instagram.com/colosseum.berlin/'),
    ('duncker-club', 'https://www.instagram.com/dunckerclub/'),
    ('konrad-tonz-bar', 'https://www.instagram.com/konradtoenzbar/'),
    ('kulturhaus-spandau', 'https://www.instagram.com/kulturhaus.spandau/'),
    ('rosa', 'https://www.instagram.com/rosaclub.berlin/'),
    ('showfenster-theater', 'https://www.instagram.com/showfenster_theater/'),
    ('sonnenraum', 'https://www.instagram.com/sonnenraum.berlin/'),
    ('soulcat', 'https://www.instagram.com/soulcat_bar_berlin/'),
    ('supamolly', 'https://www.instagram.com/supamolly_berlin/'),
    ('wild-at-heart', 'https://www.instagram.com/wildatheartberlin/')
) AS link (slug, url)
WHERE venue.slug = link.slug
  AND venue.instagram_url IS NULL;
