-- The last three venue coordinates, 207 to 417 metres out (#329).
--
-- V010 and V011 corrected twenty-nine. These three were the only venues of eighty-six where nothing
-- corroborated what we stored: OpenStreetMap holds no POI named `Roadrunner's Paradise` or
-- `VOID Club` under any variant, and `Panke Culture` resolves only to the same object its address
-- already matched. One source is not the bar the other corrections met, so each was confirmed
-- against a second, independent one. The two agreed to within 10-45 m.
--
-- **That completes the estate.** Of eighty-six venues, thirty-two carried a coordinate between 207 m
-- and 1810 m from their own address. Every one of them would have put a pin in the wrong place and
-- dropped the venue out of a radius search without reporting anything.
--
-- Three remain deliberately untouched. AMT and Badehaus have two sources that disagree with each
-- other by more than the threshold, so neither can call ours wrong. Mikropol's address is the
-- problem rather than its coordinate: all three sources disagree with one another.
--
-- **Coordinates are from OpenStreetMap, (c) OpenStreetMap contributors, ODbL**, which permits storing
-- a derived value and requires attribution where it is displayed. The second source decided which
-- rows to change and was never copied.
--
-- Unqualified table name, deliberately (ADR-004). Each UPDATE names the value it replaces.

-- Roadrunner's Paradise: 417 m out
UPDATE venue SET latitude = 52.52953, longitude = 13.41259
WHERE slug = 'roadrunner-s-paradise' AND latitude = 52.5306 AND longitude = 13.4185;

-- VOID Club: 275 m out
UPDATE venue SET latitude = 52.50754, longitude = 13.47581
WHERE slug = 'void-club' AND latitude = 52.5065 AND longitude = 13.4795;

-- Panke Culture: 207 m out
UPDATE venue SET latitude = 52.545, longitude = 13.37424
WHERE slug = 'panke-culture' AND latitude = 52.54387 AND longitude = 13.37669;
