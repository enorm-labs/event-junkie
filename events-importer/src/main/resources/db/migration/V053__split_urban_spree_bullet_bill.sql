-- Urban Spree billed two bands on one line, separated by a bullet and each followed by its country
-- and label, and nothing read the bullet as a separator: `New Candys (IT, Fuzz Club) • BLKE (DE,
-- Tonzonen)` was stored as one artist (#1818). `splitHeadlinerTitle` now reads a bullet list whose
-- every segment carries such an annotation, which leaves a night's strapline alone.
--
-- The parser cannot repair this row. The night is 2026-08-31 and past, so it is never re-imported,
-- and OrphanArtistSweep only takes a row no event bills. So the split is done here: both acts are
-- billed under their own names, and the fused row goes. `V050` and `V052` deleted their equivalents
-- instead, and that left a past night with no act at all; this keeps the billing.
--
-- `Blke` already holds an artist row, with a Bandcamp link an enrichment run found, so it is reused
-- rather than inserted. `New Candys` does not, and is created. Both names are what
-- `canonicalArtistName` produces from the title, and each slug is `slugify` of its name — asserted
-- in `SplitBulletBillMigrationTest` rather than trusted, because a survivor whose slug and name
-- disagree is re-minted under a second row by the next import (#1343).
--
-- Every step is keyed on slug and is a no-op where the row is absent, so a database that never
-- scraped this night — every developer's — is left alone. Billing order follows the title.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TEMPORARY TABLE bullet_bill_act (
    slug          TEXT NOT NULL,
    name          TEXT NOT NULL,
    billing_order INT  NOT NULL
) ON COMMIT DROP;

INSERT INTO bullet_bill_act (slug, name, billing_order) VALUES
    ('new-candys', 'New Candys', 0),
    ('blke',       'Blke',       1);

-- 1. An act without a row yet is created. One insert per missing slug, and the UNIQUE on slug is
--    what makes the guard exact rather than approximate.
INSERT INTO artist (name, slug)
SELECT a.name, a.slug
FROM bullet_bill_act a
WHERE EXISTS (SELECT 1 FROM artist f WHERE f.slug = 'new-candys-it-fuzz-club-blke-de-tonzonen')
  AND NOT EXISTS (SELECT 1 FROM artist e WHERE e.slug = a.slug);

-- 2. Every event the fused row billed now bills both acts, keeping the role and the stage it was
--    billed under. An event already linked to one of them keeps that link, so the UNIQUE on
--    (event_id, artist_id) is never touched.
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage)
SELECT ea.event_id, s.id, ea.role, a.billing_order, ea.stage
FROM event_artist ea
JOIN artist f ON f.id = ea.artist_id AND f.slug = 'new-candys-it-fuzz-club-blke-de-tonzonen'
JOIN bullet_bill_act a ON TRUE
JOIN artist s ON s.slug = a.slug
WHERE NOT EXISTS (
    SELECT 1 FROM event_artist d WHERE d.event_id = ea.event_id AND d.artist_id = s.id
);

-- 3. The fused row, gone. The FK cascade on event_artist removes its link.
DELETE FROM artist
WHERE slug = 'new-candys-it-fuzz-club-blke-de-tonzonen';
