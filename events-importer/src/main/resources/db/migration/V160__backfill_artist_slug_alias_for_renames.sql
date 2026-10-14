-- The five artists renamed on production on 2026-10-14, before V159 kept a former slug (#3289). Each gets its old slug as
-- an alias, so the next import of a night that still bills the old name resolves to the renamed artist.
--
-- Keyed on the current slug, never an id: the ids differ per database. A pair is skipped when an artist still holds the
-- former slug (the rename never happened there, as on staging) or an alias row for it exists.
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

INSERT INTO artist_slug_alias (slug, artist_id)
SELECT renamed.former_slug, a.id
FROM (VALUES ('a-night-of-beatbox-with-marvelin', 'marvelin'),
             ('empire-of-giants-jahresabschluss', 'empire-of-giants'),
             ('orgis-weihnachtsshow', 'king-orgasmus-one'),
             ('rundfunkchorlounge', 'rundfunkchor-berlin'),
             ('bad-company-legacy-dave-colwell', 'bad-company-legacy')) AS renamed (former_slug, current_slug)
         JOIN artist a ON a.slug = renamed.current_slug
WHERE NOT EXISTS (SELECT 1 FROM artist held WHERE held.slug = renamed.former_slug)
  AND NOT EXISTS (SELECT 1 FROM artist_slug_alias alias WHERE alias.slug = renamed.former_slug);
