-- Six coordinates that no geocoder could have produced, from the venue operator (#357).
--
-- **Five of them share one address.** Astra Kulturhaus, Badehaus, Der Weiße Hase, MAAYA and Urban
-- Spree all give theirs as Revaler Str. 99 -- the whole RAW-Gelände -- so every geocoder answers all
-- five with one point, and V014 could not touch them: a distance measured against a shared point is
-- measuring the size of the site. Their stored points were the only values in this table that
-- nothing outside it had ever corroborated.
--
-- The operator walked the site and gave five points. OpenStreetMap holds a named node for each of
-- the five, and the two agree to between 2 m and 35 m. **The values below are OpenStreetMap's**,
-- because the survey confirms them and that keeps the whole table ODbL rather than adding five more
-- coordinates under the Maps Platform terms (#357 draws an open-source map). The survey is the
-- reason to believe them; the node is the thing we are allowed to store.
--
-- Unqualified table name, deliberately (ADR-004). Each UPDATE names the value it replaces.

-- Astra Kulturhaus: moves 17 m; survey and node agree.
UPDATE venue SET latitude = 52.507383, longitude = 13.451895
WHERE slug = 'astra-kulturhaus' AND latitude = 52.50724 AND longitude = 13.4518;

-- Badehaus: moves 181 m; survey and node agree.
UPDATE venue SET latitude = 52.507582, longitude = 13.45515
WHERE slug = 'badehaus' AND latitude = 52.5078 AND longitude = 13.4525;

-- Der Weiße Hase: moves 106 m; survey and node agree.
UPDATE venue SET latitude = 52.507583, longitude = 13.454532
WHERE slug = 'der-weisse-hase' AND latitude = 52.507 AND longitude = 13.4533;

-- MAAYA: moves 54 m; survey and node agree.
UPDATE venue SET latitude = 52.507297, longitude = 13.452741
WHERE slug = 'maaya' AND latitude = 52.50775 AND longitude = 13.45247;

-- Urban Spree: moves 40 m; survey and node agree.
UPDATE venue SET latitude = 52.507612, longitude = 13.451645
WHERE slug = 'urban-spree' AND latitude = 52.5075 AND longitude = 13.4522;

-- Gärten der Welt is a hundred hectares, so no lookup can answer where it is -- the useful pin is
-- the entrance, which is a decision rather than a fact. The operator names Blumberger Damm 44 as the
-- point to use. The stored coordinate reverse-geocodes to Gottfried-Funeck-Weg, a path inside the
-- park; Google puts that address 494 m away on Eisenacher Straße, which is not Blumberger Damm at
-- all, and OpenStreetMap holds the addressed house node itself. That is the 370 m below.
UPDATE venue SET latitude = 52.538103, longitude = 13.569622
WHERE slug = 'garten-der-welt' AND latitude = 52.53726 AND longitude = 13.57492;
