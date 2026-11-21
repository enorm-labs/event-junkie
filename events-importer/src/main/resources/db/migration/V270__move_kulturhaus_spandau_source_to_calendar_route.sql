-- Kulturhaus Spandau's theme dropped its events list (#5365). `/wp-json/eventmanager/v1/events` answers 404, and the
-- programme is now the per-month `/calendar/` route, which the importer asks for by `month` and `year`. The admin API
-- changes no source URL, so the row moves here.
UPDATE event_source
SET url           = 'https://kulturhaus-spandau.de/wp-json/eventmanager/v1/calendar/',
    etag          = NULL,
    last_modified = NULL,
    updated_at    = now(),
    version       = version + 1
WHERE slug = 'kulturhaus-spandau';
