-- Removes stored description and image URL for every source that forbids them (#807).
--
-- PROHIBITED already withheld both fields from public responses, which answers § 19a UrhG. It did
-- not answer § 16 UrhG: we went on holding the reproduction. SCRAPING_POSITION.md §3.1 requires a
-- justification for both acts, so a source we recorded as prohibited must not keep either.
--
-- This is a data change rather than a schema change, and that is deliberate. The importer now
-- declines to store these fields, which repairs every event it re-imports -- but a past event is
-- never scraped again, so nothing else would ever clear it. A migration is the only mechanism that
-- runs exactly once in each environment.

UPDATE event e
SET description = NULL
FROM event_source es
WHERE e.event_source_id = es.id
  AND es.description_licence = 'PROHIBITED'
  AND e.description IS NOT NULL;

UPDATE event e
SET image_url = NULL
FROM event_source es
WHERE e.event_source_id = es.id
  AND es.image_licence = 'PROHIBITED'
  AND e.image_url IS NOT NULL;
