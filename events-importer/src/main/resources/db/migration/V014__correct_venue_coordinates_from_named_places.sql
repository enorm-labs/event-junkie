-- Eighteen venue coordinates, 104 m to 183 m out, corrected against two sources that agree with
-- each other (#357).
--
-- **The method is the point.** V010 to V012 compared our coordinate with a geocoded *address*, which
-- answers "where is this building" and not "where is this venue". Asking Google for the venue by
-- *name* returns the business, and OpenStreetMap holds a named node for most of them. Where those
-- two independent answers land on top of each other and both sit far from our point, the point is
-- wrong. Every row below has the two agreeing within 50 m; most agree within 10 m.
--
-- **The stored value is OpenStreetMap's, as it is everywhere else in this table.** (c) OpenStreetMap
-- contributors, ODbL, which permits the stored value and requires credit where it is displayed
-- (#357). Google decided which rows to change and none of its numbers are copied here.
--
-- Six of the twenty-four flagged rows are deliberately not here. `Matrix`, `Uber Eats Music Hall`
-- and `Neue Zukunft` are inside 100 m of the agreed point, and only the address geocode made them
-- look wrong. `Panke Culture` is 10 m from its own named node, which settles the doubt V012 left.
-- `Alte Kantine` and `Duncker Club` have no usable second source: OpenStreetMap's nearest match for
-- `Alte Kantine` is an allotment in Tegel, 8.5 km away. **Both addresses are since confirmed by the
-- venue's operator**, so what stays unverified about them is only the point. `Alte Kantine` shares
-- the Kulturbrauerei with `Soda Club` and `Frannz Club`, which are corrected below and land 89 m and
-- 103 m from it -- three buildings in one complex, not three guesses at one door.
--
-- Slugs are machine-generated, never spelled by hand: `Saalchen` is what `Säälchen` slugs to, and a
-- slug that does not match is an UPDATE that changes nothing and reports success.
--
-- Unqualified table name, deliberately (ADR-004). Each UPDATE names the value it replaces.


-- Columbia Theater: 183 m out. By address 176 m, by name 174 m, the two sources 20 m apart.
UPDATE venue SET latitude = 52.48473, longitude = 13.391437
WHERE slug = 'columbia-theater' AND latitude = 52.4838 AND longitude = 13.3892;

-- Huxleys Neue Welt: 183 m out. By address 179 m, by name 179 m, the two sources 73 m apart.
-- The one row here whose sources disagree by more than 50 m, taken anyway because both of them put
-- us about 180 m away: being wrong by 180 m is worse than being uncertain by 73 m.
--
-- The row settles it against itself. Its address is `Hasenheide 107-113, 10967`, confirmed by the
-- venue's operator, and the coordinate being replaced reverse-geocodes to `Lucy-Lameck-Straße 4,
-- 12049` -- a different street in a different postal code. The same self-contradiction that
-- convicted AMT in V013.
--
-- **The district stays `neukoelln`, and that needed a human.** Hasenheide is the Kreuzberg-Neukölln
-- line, and the machines cannot hold it: OpenStreetMap puts both the old and the new point in
-- Friedrichshain-Kreuzberg while naming the Ortsteil `Neukölln` for one and `Kreuzberg` for the
-- other, and Google returns no borough for this address at all. The venue's operator confirms
-- Neukölln. A borough is the column V009 closed because a wrong one is invisible, so it is worth
-- knowing that no geocoder settled this one.
UPDATE venue SET latitude = 52.486865, longitude = 13.422321
WHERE slug = 'huxleys-neue-welt' AND latitude = 52.48542 AND longitude = 13.42361;

-- Ritter Butzke: 168 m out. By address 180 m, by name 179 m, the two sources 25 m apart.
UPDATE venue SET latitude = 52.503012, longitude = 13.408136
WHERE slug = 'ritter-butzke' AND latitude = 52.50438 AND longitude = 13.40707;

-- Soda Club: 165 m out. By address 129 m, by name 172 m, the two sources 14 m apart.
UPDATE venue SET latitude = 52.539523, longitude = 13.414245
WHERE slug = 'soda-club' AND latitude = 52.53951 AND longitude = 13.4118;

-- Modus Berlin: 158 m out. By address 180 m, by name 164 m, the two sources 24 m apart.
-- The address is corrected too, and it was neither of the two guesses. The venue's operator confirms
-- Ritterstraße 24-27; Lobeckstraße is only the Impressum, and the stored Ritterstraße 26 is Ritter
-- Butzke's number in the same block. OpenStreetMap holds 24-27 at a point 50 m from the one below.
UPDATE venue SET address = 'Ritterstr. 24-27', latitude = 52.50304, longitude = 13.407845
WHERE slug = 'modus-berlin' AND address = 'Ritterstr. 26'
  AND latitude = 52.50438 AND longitude = 13.40707;

-- MAXXIM: 152 m out. By address 145 m, by name 151 m, the two sources 2 m apart.
UPDATE venue SET latitude = 52.501906, longitude = 13.330836
WHERE slug = 'maxxim' AND latitude = 52.50287 AND longitude = 13.32924;

-- Supamolly: 149 m out. By address 150 m, by name 150 m, the two sources 9 m apart.
UPDATE venue SET latitude = 52.510611, longitude = 13.471417
WHERE slug = 'supamolly' AND latitude = 52.5119 AND longitude = 13.4708;

-- Arcanoa: 148 m out. By address 150 m, by name 150 m, the two sources 8 m apart.
UPDATE venue SET latitude = 52.488006, longitude = 13.386809
WHERE slug = 'arcanoa' AND latitude = 52.488 AND longitude = 13.389;

-- Uber Arena: 148 m out. By address 144 m, by name 140 m, the two sources 11 m apart.
UPDATE venue SET latitude = 52.506369, longitude = 13.443765
WHERE slug = 'uber-arena' AND latitude = 52.50506 AND longitude = 13.44339;

-- SO36: 147 m out. By address 153 m, by name 148 m, the two sources 4 m apart.
UPDATE venue SET latitude = 52.500391, longitude = 13.422161
WHERE slug = 'so36' AND latitude = 52.50169 AND longitude = 13.42256;

-- Columbiahalle: 139 m out. By address 125 m, by name 135 m, the two sources 4 m apart.
UPDATE venue SET latitude = 52.484738, longitude = 13.392546
WHERE slug = 'columbiahalle' AND latitude = 52.48452 AND longitude = 13.39052;

-- Festsaal Kreuzberg: 121 m out. By address 210 m, by name 137 m, the two sources 27 m apart.
UPDATE venue SET latitude = 52.496823, longitude = 13.451556
WHERE slug = 'festsaal-kreuzberg' AND latitude = 52.4966 AND longitude = 13.4498;

-- Frannz Club: 113 m out. By address 72 m, by name 118 m, the two sources 6 m apart.
UPDATE venue SET latitude = 52.538211, longitude = 13.412688
WHERE slug = 'frannz-club' AND latitude = 52.53923 AND longitude = 13.41266;

-- Havanna: 113 m out. By address 129 m, by name 110 m, the two sources 19 m apart.
UPDATE venue SET latitude = 52.48527, longitude = 13.353449
WHERE slug = 'havanna' AND latitude = 52.4854 AND longitude = 13.3551;

-- Metropol: 106 m out. By address 103 m, by name 101 m, the two sources 7 m apart.
UPDATE venue SET latitude = 52.49894, longitude = 13.352705
WHERE slug = 'metropol' AND latitude = 52.49939 AND longitude = 13.35408;

-- Säälchen: 106 m out. By address 121 m, by name 110 m, the two sources 4 m apart.
UPDATE venue SET latitude = 52.511725, longitude = 13.426338
WHERE slug = 'saalchen' AND latitude = 52.51157 AND longitude = 13.42479;

-- Tempodrom: 105 m out. By address 105 m, by name 102 m, the two sources 3 m apart.
UPDATE venue SET latitude = 52.501606, longitude = 13.381191
WHERE slug = 'tempodrom' AND latitude = 52.5025 AND longitude = 13.38167;

-- Max-Schmeling-Halle: 104 m out. By address 169 m, by name 85 m, the two sources 18 m apart.
UPDATE venue SET latitude = 52.5448, longitude = 13.404494
WHERE slug = 'max-schmeling-halle' AND latitude = 52.54556 AND longitude = 13.40361;
