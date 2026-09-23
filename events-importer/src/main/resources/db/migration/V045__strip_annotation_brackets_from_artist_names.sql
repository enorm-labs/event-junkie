-- An artist name kept a bracket the venue wrote next to the act: a country code, a band or label,
-- a show note, a second act (#1761). The shared rules now strip these on import. This repairs the
-- rows that exist, because there is no re-seed on staging or production.
--
-- The renames are the staging rows the new rules change, keyed on slug. Each is a no-op where the
-- row is absent or its new slug is already taken, so production changes only what it shares with
-- staging. A renamed row is checked against MusicBrainz again, under its real name.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

-- 1. Two upcoming rows whose stripped slug already exists. The next import would move the link
--    anyway; the survivor takes the loser's billing, then the loser goes.
WITH merged(loser_slug, survivor_slug) AS (VALUES
    ('paula-paula-zusatzshow', 'paula-paula'),
    ('sean-steinfeger-ohshitf-ckyes', 'sean-steinfeger')
)
INSERT INTO event_artist (event_id, artist_id, role, billing_order, stage, title_derived)
SELECT ea.event_id, survivor.id, ea.role, ea.billing_order, ea.stage, ea.title_derived
FROM merged m
JOIN artist loser ON loser.slug = m.loser_slug
JOIN artist survivor ON survivor.slug = m.survivor_slug
JOIN event_artist ea ON ea.artist_id = loser.id
ON CONFLICT (event_id, artist_id) DO NOTHING;

WITH merged(loser_slug, survivor_slug) AS (VALUES
    ('paula-paula-zusatzshow', 'paula-paula'),
    ('sean-steinfeger-ohshitf-ckyes', 'sean-steinfeger')
)
DELETE FROM artist loser
USING merged m, artist survivor
WHERE loser.slug = m.loser_slug
  AND survivor.slug = m.survivor_slug;

-- 2. Rename where the stripped slug is free. A past-only row whose slug is taken keeps its bracket:
--    `Tina (De)` is not evidence that it is the same person as `Tina`.
WITH renamed(old_slug, new_slug, new_name) AS (VALUES
    ('anderson-us', 'anderson', 'Anderson'),
    ('baklaxa-live', 'baklaxa', 'Bakläxa'),
    ('cee-hybrid-live', 'cee', 'Cee'),
    ('crs-dnk', 'crs', 'Crs'),
    ('das-beat-live', 'das-beat', 'Das Beat'),
    ('david-j-bauhaus-love-rockets', 'david-j', 'David J'),
    ('dela-moon-usa', 'dela-moon', 'Dela Moon'),
    ('der-opium-queen-live', 'der-opium-queen', 'Der Opium Queen'),
    ('dornika-live', 'dornika', 'Dornika'),
    ('dosenstolz-feat-tancred', 'dosenstolz', 'Dosenstolz'),
    ('dreadnought-can', 'dreadnought', 'Dreadnought'),
    ('etienne-live', 'etienne', 'Etienne'),
    ('flow-rea-est', 'flow-rea', 'Flow Rea'),
    ('fritzi-ernst-live', 'fritzi-ernst', 'Fritzi Ernst'),
    ('goat-jp', 'goat', 'goat'),
    ('la-rat-live', 'la-rat', 'La Rat'),
    ('lucas-depta-violetta-de', 'lucas-depta-violetta', 'Lucas Depta & Violetta'),
    ('marcel-fengler-imf-ostgut-ton', 'marcel-fengler', 'Marcel Fengler'),
    ('pablo-ulises-lienhard-live', 'pablo-ulises-lienhard', 'Pablo Ulises Lienhard'),
    ('pes-de-barro-live-band', 'pes-de-barro', 'Pés de Barro'),
    ('sameer-rahat-live', 'sameer-rahat', 'Sameer Rahat'),
    ('schatz-live', 'schatz', 'Schatz'),
    ('section-63-uk', 'section-63', 'Section 63'),
    ('steve-norman-von-spandau-ballet-the-sleevz', 'steve-norman-the-sleevz', 'Steve Norman & The Sleevz'),
    ('stratagem-v-fire-at-work-pawel-jankiewicz-peppe-bottiglieri', 'stratagem-v', 'Stratagem V'),
    ('surreal-de', 'surreal', 'Surreal'),
    ('sylk-de-malor-records-surge', 'sylk', 'Sylk'),
    ('trixie-uk', 'trixie', 'Trixie'),
    ('voicex-live', 'voicex', 'Voicex'),
    ('warhammer-corrode', 'warhammer', 'Warhammer')
)
UPDATE artist a
SET slug                   = r.new_slug,
    name                   = r.new_name,
    musicbrainz_id         = NULL,
    musicbrainz_match      = 'UNCHECKED',
    musicbrainz_checked_at = NULL
FROM renamed r
WHERE a.slug = r.old_slug
  AND NOT EXISTS (SELECT 1 FROM artist t WHERE t.slug = r.new_slug);

-- 3. Bracketed rows no event bills: leftovers of earlier fixes (#314, #1561), which minted a new row
--    and left the old one with its public page.
DELETE FROM artist a
WHERE a.name LIKE '%(%'
  AND NOT EXISTS (SELECT 1 FROM event_artist ea WHERE ea.artist_id = a.id);
