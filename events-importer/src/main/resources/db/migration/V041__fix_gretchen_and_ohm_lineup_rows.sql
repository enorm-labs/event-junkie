-- Gretchen kept a leading "+" on an act added after the main billing, and fused two lineup lines
-- whose only <br> sat inside an <em> (#1755). OHM billed a live painter and a visual installation
-- as DJs (#1756). The scrapers are fixed; this repairs the rows that exist, because there is no
-- re-seed on staging or production. Each statement is a no-op where the row is absent.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

-- The slug is already right, so a rename keeps the row and its links; the import never rewrites a name.
UPDATE artist
SET name = 'Mittelmeer Monologe'
WHERE slug = 'mittelmeer-monologe'
  AND name = '+ Mittelmeer Monologe';

-- Names no act has. The FK cascade on event_artist removes the links; the next import links
-- `sean-steinfeger` to the Gretchen night and leaves the OHM credits out.
DELETE FROM artist
WHERE slug IN (
    'guestsopening-dj-set-by-sean-steinfeger',
    'teo-clavero-live-painting',
    's-o-n-o-s-sonic-visual-installation'
);
