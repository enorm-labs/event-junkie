-- Seven venues whose address, postal code or coordinate named the wrong place (#357).
--
-- These are not the residue of V010 to V012, which corrected coordinates. Five of the seven are
-- wrong in the *address*, which is why an audit of coordinates could not see them: geocoding a
-- wrong address confirms the wrong place, however precise the match. Two of those five stored no
-- house number at all, so the geocoder answered with the middle of a street and the distance
-- measured the street rather than the error.
--
-- **Every slug below was checked against `SlugGenerator`, not spelled by hand.** `Heideglühen` slugs
-- to `heidegluhen`: Slugify strips the diaeresis by decomposition rather than expanding `ü` to `ue`,
-- so the spelling the venue's own domain uses would have matched no row and reported success.
--
-- Two of them are the cases #329 recorded as unresolved and handed to this issue. What settles them
-- now is a second query path rather than a second source: asking Google for the venue by *name*
-- returns a `night_club` or `point_of_interest` result, and that answers a different question than
-- asking for its address. #329 found the same thing with OpenStreetMap, where the name pass caught
-- three venues the address pass had excused.
--
-- **Every value below is OpenStreetMap's or the venue's own, except two coordinates.** Google is the
-- detector rather than the source, for the reason V010 gives: a coordinate in this table is ODbL and
-- needs credit where it is displayed. `AMT` and `Sonnenraum` keep a Google coordinate because no
-- other source resolves either address to a building -- OpenStreetMap has no house number 114 on
-- Dircksenstraße and no Eichenstraße 4a in 12435. Both addresses are the venue's own; only the two
-- points are Google's, and each statement says so.
--
-- Unqualified table name, deliberately (ADR-004). Each UPDATE names the value it replaces.

-- Bi Nuu carried Monarch's address. Both rows read `Skalitzer Str. 134, 10999`, which is Monarch's:
-- Monarch sits 59 m from what that address geocodes to and Bi Nuu 1535 m. The venue says it is
-- `direkt im U-Bahnhof Schlesisches Tor`, this file's own divider comment already said Schlesisches
-- Tor beside the wrong street, and asking for `Bi Nuu` by name returns the station.
--
-- **The coordinate was right and is left alone.** OpenStreetMap's `Bi Nuu` node is 36 m from it and
-- Google's is 47 m, so nothing here is worth a metre of ODbL argument.
--
-- No house number, confirmed by the venue's operator. The club is the station, and `Berghain` is
-- already stored the same way for the same reason: a street with no number is what the place has.
UPDATE venue SET address = 'Schlesisches Tor', postal_code = '10997'
WHERE slug = 'bi-nuu' AND address = 'Skalitzer Str. 134' AND postal_code = '10999';

-- Mikropol is in the Metropol building at Nollendorfplatz, not in Naumannstraße a kilometre south.
-- #329 left it alone because its three sources disagreed, and correctly diagnosed the address as
-- the cause without being able to fix it.
--
-- OpenStreetMap holds an `events_venue` node named `Mikropol` tagged `Nollendorfplatz 5, 10777`
-- **and carrying this venue's own website**, so the match is by identity rather than by proximity.
-- Google's name lookup lands 4 m from that node. The coordinate below is OpenStreetMap's.
UPDATE venue SET address = 'Nollendorfplatz 5', postal_code = '10777',
                 latitude = 52.498979, longitude = 13.352977
WHERE slug = 'mikropol' AND address = 'Naumannstr. 33' AND postal_code = '10829';

-- AMT's coordinate was never on Dircksenstraße. It reverse-geocodes to Brückenstraße 5-6a at postal
-- code 10179, while the row itself said 10178 and the club's own site says `Dircksenstraße 114,
-- 10178 Berlin`. The row disagreed with itself, which is the one thing #329's two sources could not
-- see, because both were asked about the address and neither about the point.
--
-- **This latitude and longitude are Google's, and the only values in this file that are.**
-- OpenStreetMap holds no house number 114 on Dircksenstraße, so nothing else resolves the address to
-- a building. Two Google query paths agree on it: the address, and the club by name, which comes
-- back as a `night_club` at the same point. #357 carries the attribution decision that follows.
UPDATE venue SET latitude = 52.520329, longitude = 13.413313
WHERE slug = 'amt' AND latitude = 52.5137 AND longitude = 13.418;

-- The postal code is 10439, from the club's own Impressum. Nothing else about the row is wrong, and
-- a postal code is not decoration: `V009` closed the district column to the twelve boroughs because
-- a field nobody validates is where the wrong values go.
UPDATE venue SET postal_code = '10439'
WHERE slug = 'duncker-club' AND postal_code = '10437';

-- Sonnenraum was given Club der Visionäre's address because it is that club's own concert room, and
-- the two were assumed to share a door. Google returns `Sonnenraum` as a point of interest 196 m
-- away at Eichenstraße 4a, 22 m from where it puts `Eichenstr. 4` — the address this database
-- already stores for MS Hoppetosse — and returns the club and the room as two places when asked for
-- both. The venue's own page calls it `die neue Konzertlocation vom Club der Visionaere`.
--
-- **The address is confirmed by the venue's operator.** That settles the street, which the sources
-- could not: OpenStreetMap holds no `Sonnenraum`, its only `Eichenstraße 4a` is in Kaulsdorf across
-- the city, and reverse-geocoding Google's point names `Am Flutgraben 2` instead.
--
-- **The coordinate is still Google's**, because no other source resolves this address to a building.
-- That is the licensing exposure named at the top of this file, and it is deliberate.
--
-- The description drops `next to`, which asserted the adjacency this statement removes. It is guarded
-- on the seeded wording, so a row an import has since rewritten keeps whatever it now holds rather
-- than having a licence-reviewed value overwritten from here (#283, V007).
UPDATE venue
SET address = 'Eichenstr. 4a', latitude = 52.496224, longitude = 13.453188,
    description = replace(description, 'concert room next to Club der Visionäre',
                                       'concert room run by Club der Visionäre')
WHERE slug = 'sonnenraum' AND address = 'Am Flutgraben 1'
  AND latitude = 52.4964 AND longitude = 13.4503;

-- Heideglühen had a street and a postal code that described somewhere else, and a coordinate that
-- was right the whole time. The row said `Beusselstraße, 10553, mitte`; the point it
-- stores is 22 m from OpenStreetMap's `Heideglühen` nightclub node and 44 m from Google's, and both
-- of those answer `Seestraße 1, 13353`. Reverse-geocoding our own point agrees, and puts it in
-- Charlottenburg-Nord rather than Moabit.
--
-- **The borough is not touched, and that is the interesting part.** Seestraße 1 is in Wedding, which
-- is an Ortsteil of Mitte, so the stored `mitte` was right all along and is left alone. Both
-- geocoders say Charlottenburg-Wilmersdorf, and **both are wrong**: number 1 sits at the west end of
-- Seestraße against the canal, where the boundary with Charlottenburg-Nord runs, and each of them
-- answered for the point rather than for the address. The venue's operator settled it.
--
-- That is the third borough in this audit that a geocoder could not supply, after `Huxleys Neue
-- Welt`, where neither had an answer, and `MS Hoppetosse`, where the two lookups disagreed with each
-- other. This is the worst of the three: the sources agreed, and agreed on the wrong thing.
-- `scripts/geocode-venues.py` says never to fill this column from them, and this is why.
--
-- The description loses `off Beusselstraße`, which the corrected address makes wrong. No coordinate
-- change: nothing here suggests the point was ever the problem.
UPDATE venue
SET address = 'Seestraße 1', postal_code = '13353',
    description = replace(description, 'nursery off Beusselstraße', 'nursery off Seestraße')
WHERE slug = 'heidegluhen' AND address = 'Beusselstraße'
  AND postal_code = '10553' AND district = 'mitte';

-- Max-Schmeling-Halle was stored with no house number, which is why the geocoder answered with the
-- middle of a square rather than a building. The venue's own imprint says `Falkplatz 1, 10437
-- Berlin`, and OpenStreetMap tags the hall the same way.
--
-- Google normalises the street to `Am Falkplatz` and is outvoted; the operator's own spelling wins.
-- The coordinate stays: the hall is larger than the disagreement, and nothing calls the point wrong.
UPDATE venue SET address = 'Falkplatz 1'
WHERE slug = 'max-schmeling-halle' AND address = 'Am Falkplatz';
