-- M-BIA moved from WordPress to Wix (#4180). The posts API answers 404; the programme is now the `Events` collection on
-- /veranstaltungen. The admin API changes no source URL, so the row moves here. The old validators belong to the old URL.
UPDATE event_source
SET url           = 'https://www.m-bia.de/veranstaltungen',
    etag          = NULL,
    last_modified = NULL,
    updated_at    = now(),
    version       = version + 1
WHERE slug = 'm-bia';
