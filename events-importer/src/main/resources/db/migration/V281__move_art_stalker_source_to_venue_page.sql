-- ART Stalker's shop root redirects to `/events`, which the shop's robots.txt disallows (#5496). The venue's page on
-- www.reservix.de is allowed and lists the whole programme. The admin API changes no source URL, so the row moves here.
UPDATE event_source
SET url           = 'https://www.reservix.de/berlin/venue/art-stalker/v41370',
    etag          = NULL,
    last_modified = NULL,
    updated_at    = now(),
    version       = version + 1
WHERE slug = 'art-stalker';
