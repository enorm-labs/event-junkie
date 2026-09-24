-- De-shouting title-cased acronyms and hyphenated names (#1846): production minted
-- `Lsd & The Search for God`, `Sdp & …`, `Ufo 361`, `$Ono$ Cliq`, `K-pop Forever!` and
-- `Lexy & K-paul`. The rules now keep the capitals, but a row keeps the name the import that
-- minted it gave it, and there is no re-seed on staging or production.
--
-- Casing only, so each slug already equals `slugify(new_name)`. Keyed on slug and the old name:
-- a row that is absent, or that a person has corrected since, is left alone.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

UPDATE artist a
SET name = r.new_name
FROM (VALUES
    ('lsd-the-search-for-god', 'Lsd & The Search for God', 'LSD & The Search for God'),
    ('sdp-the-capital-dance-orchestra', 'Sdp & The Capital Dance Orchestra', 'SDP & The Capital Dance Orchestra'),
    ('ufo-361', 'Ufo 361', 'UFO 361'),
    ('ono-cliq', '$Ono$ Cliq', '$ONO$ Cliq'),
    ('k-pop-forever', 'K-pop Forever!', 'K-Pop Forever!'),
    ('lexy-k-paul', 'Lexy & K-paul', 'Lexy & K-Paul')
) AS r(slug, old_name, new_name)
WHERE a.slug = r.slug
  AND a.name = r.old_name;
