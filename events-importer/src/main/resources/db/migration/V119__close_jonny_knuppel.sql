-- Jonny Knüppel leaves the Greifswalder Straße site after its last season, and 2026-10-31 is its last day open (ADR-046,
-- #2731). Its site lists no more nights, so the programme link goes too. Nothing re-seeds the clusters, so the closure is
-- a migration. It does nothing where the venue is missing.
UPDATE venue
SET closed_on     = DATE '2026-10-31',
    programme_url = NULL
WHERE slug = 'jonny-knuppel';
