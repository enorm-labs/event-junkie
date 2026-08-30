-- Corrects nineteen more venue coordinates, 209 to 1810 metres out (#329).
--
-- V010 fixed ten. These nineteen were found by asking OpenStreetMap a *second, different question*:
-- search by venue name bounded to Berlin, rather than by the address the row holds. Where the two
-- queries land on the same place and neither is ours, that is two signals rather than one.
--
-- Each was then confirmed against a second, independent source. The two agreed with each other to
-- within 11-29 m for seventeen of them, and 67-76 m for the two resolved from the address search.
--
-- **Four candidates were checked and deliberately left alone.** Loge is correct -- the independent
-- source lands 16 m from the stored value, and the name search had matched a different place. AMT
-- and Badehaus failed the agreement bar: their two sources sit 135 m and 153 m apart, which is not
-- close enough to call ours wrong. The bar is the point of it; stretching it to gather two more
-- rows would make every row here weaker.
--
-- **Coordinates are from OpenStreetMap, (c) OpenStreetMap contributors, ODbL.** That licence permits
-- storing a derived value and requires attribution where it is displayed. The second source decided
-- which rows to change and was never copied, because its terms restrict storing coordinates.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004). Each UPDATE names the value it expects to replace, so a row
-- somebody corrected by hand is left alone rather than moved back.

-- Hole 44: 1810 m out
UPDATE venue SET latitude = 52.46435, longitude = 13.43338
WHERE slug = 'hole-44' AND latitude = 52.47993 AND longitude = 13.42565;

-- Parkbühne Wuhlheide: 1265 m out
UPDATE venue SET latitude = 52.46234, longitude = 13.5454
WHERE slug = 'parkbuhne-wuhlheide' AND latitude = 52.46177 AND longitude = 13.56405;

-- Lido: 516 m out
UPDATE venue SET latitude = 52.49922, longitude = 13.44507
WHERE slug = 'lido' AND latitude = 52.49778 AND longitude = 13.43782;

-- Club OST: 461 m out
UPDATE venue SET latitude = 52.49707, longitude = 13.46503
WHERE slug = 'club-ost' AND latitude = 52.5008 AND longitude = 13.468;

-- Wild at Heart: 399 m out
UPDATE venue SET latitude = 52.49749, longitude = 13.43111
WHERE slug = 'wild-at-heart' AND latitude = 52.4977 AND longitude = 13.437;

-- Klunkerkranich: 373 m out
UPDATE venue SET latitude = 52.48196, longitude = 13.43277
WHERE slug = 'klunkerkranich' AND latitude = 52.47917 AND longitude = 13.43583;

-- Monster Ronson's Ichiban Karaoke: 347 m out
UPDATE venue SET latitude = 52.50519, longitude = 13.44842
WHERE slug = 'monster-ronson-s-ichiban-karaoke' AND latitude = 52.5083 AND longitude = 13.4489;

-- Schokoladen: 345 m out
UPDATE venue SET latitude = 52.52974, longitude = 13.39718
WHERE slug = 'schokoladen' AND latitude = 52.53264 AND longitude = 13.39897;

-- Quasimodo: 342 m out
UPDATE venue SET latitude = 52.50593, longitude = 13.32837
WHERE slug = 'quasimodo' AND latitude = 52.50597 AND longitude = 13.32332;

-- MS Hoppetosse: 311 m out
UPDATE venue SET latitude = 52.49751, longitude = 13.45476
WHERE slug = 'ms-hoppetosse' AND latitude = 52.4956 AND longitude = 13.4514;

-- Bar jeder Vernunft: 297 m out
UPDATE venue SET latitude = 52.49801, longitude = 13.32959
WHERE slug = 'bar-jeder-vernunft' AND latitude = 52.4954 AND longitude = 13.3286;

-- Heimathafen Neukölln: 286 m out
UPDATE venue SET latitude = 52.47702, longitude = 13.43992
WHERE slug = 'heimathafen-neukolln' AND latitude = 52.47956 AND longitude = 13.43918;

-- Gretchen: 285 m out
UPDATE venue SET latitude = 52.49556, longitude = 13.38792
WHERE slug = 'gretchen' AND latitude = 52.49483 AND longitude = 13.38387;

-- Crack Bellmer: 276 m out
UPDATE venue SET latitude = 52.50759, longitude = 13.45477
WHERE slug = 'crack-bellmer' AND latitude = 52.5077 AND longitude = 13.4507;

-- LARK: 271 m out
UPDATE venue SET latitude = 52.51302, longitude = 13.42278
WHERE slug = 'lark' AND latitude = 52.51361 AND longitude = 13.41889;

-- Junction Bar: 255 m out
UPDATE venue SET latitude = 52.49149, longitude = 13.39325
WHERE slug = 'junction-bar' AND latitude = 52.49076 AND longitude = 13.38969;

-- Privatclub: 254 m out
UPDATE venue SET latitude = 52.50007, longitude = 13.43485
WHERE slug = 'privatclub' AND latitude = 52.49985 AND longitude = 13.43112;

-- migas: 217 m out
UPDATE venue SET latitude = 52.54374, longitude = 13.36771
WHERE slug = 'migas' AND latitude = 52.54186 AND longitude = 13.36684;

-- Clash: 209 m out
UPDATE venue SET latitude = 52.49196, longitude = 13.38856
WHERE slug = 'clash' AND latitude = 52.4917 AND longitude = 13.3855;
