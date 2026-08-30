-- Corrects ten venue coordinates that were hundreds of metres from their own address (#329).
--
-- Found by comparing every stored coordinate with what OpenStreetMap returns for the address the
-- row already holds, then confirming each one against a second, independent source. The two agreed
-- with each other to within 13-100 m and disagreed with us by 326-1808 m. One source could be
-- wrong; two that agree while we do not is the case worth acting on.
--
-- **Coordinates are from OpenStreetMap, (c) OpenStreetMap contributors, ODbL.** That licence permits
-- storing a derived value and requires attribution. Google's numbers were used only to decide, never
-- copied: their terms restrict storing coordinates and this project does not need that argument.
-- Attribution has to appear wherever these are displayed, which is the map feature rather than here.
--
-- The failure this repairs is the quiet one. A pin in the wrong place gets reported. A radius search
-- that silently omits a venue does not, and 500 m is enough to drop one.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).
--
-- Keyed on slug, and each UPDATE names the value it expects to replace. A row somebody already
-- corrected by hand is left alone rather than moved back.

-- Theater im Delphi: 1808 m out
UPDATE venue SET latitude = 52.5519, longitude = 13.43116
WHERE slug = 'theater-im-delphi' AND latitude = 52.5539 AND longitude = 13.4577;

-- Kulturhaus Insel Berlin: 809 m out
UPDATE venue SET latitude = 52.48741, longitude = 13.48136
WHERE slug = 'kulturhaus-insel-berlin' AND latitude = 52.48694 AND longitude = 13.46944;

-- Zenner: 783 m out
UPDATE venue SET latitude = 52.48731, longitude = 13.47748
WHERE slug = 'zenner' AND latitude = 52.4897 AND longitude = 13.4666;

-- Renate: 568 m out
UPDATE venue SET latitude = 52.49739, longitude = 13.46531
WHERE slug = 'renate' AND latitude = 52.5025 AND longitude = 13.46528;

-- Kulturhaus Peter Edel: 549 m out
UPDATE venue SET latitude = 52.55092, longitude = 13.46157
WHERE slug = 'kulturhaus-peter-edel' AND latitude = 52.55583 AND longitude = 13.46083;

-- silent green: 542 m out
UPDATE venue SET latitude = 52.54569, longitude = 13.36649
WHERE slug = 'silent-green' AND latitude = 52.5455 AND longitude = 13.3745;

-- Neue Zukunft: 529 m out
UPDATE venue SET latitude = 52.49842, longitude = 13.46674
WHERE slug = 'neue-zukunft' AND latitude = 52.5011 AND longitude = 13.4732;

-- Humboldthain Club: 517 m out
UPDATE venue SET latitude = 52.54435, longitude = 13.37859
WHERE slug = 'humboldthain-club' AND latitude = 52.54494 AND longitude = 13.38617;

-- Zitadelle: 335 m out
UPDATE venue SET latitude = 52.54103, longitude = 13.21274
WHERE slug = 'zitadelle' AND latitude = 52.541 AND longitude = 13.2177;

-- Cosmic Comedy Club: 326 m out
UPDATE venue SET latitude = 52.5298, longitude = 13.41
WHERE slug = 'cosmic-comedy-club' AND latitude = 52.5321 AND longitude = 13.413;
