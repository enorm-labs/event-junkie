-- Corrects three venue districts and closes the column to Berlin's twelve boroughs (#329).
--
-- The field was a free-form nullable string, and that is how the wrong values got in. Nothing was
-- wrong enough to fail: a filter on a borough simply returned fewer venues and reported success.
-- The map shows a missing pin and somebody reports it. A radius search that quietly omits a venue is
-- noticed by nobody, which is why this is worth a migration rather than a note.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

-- Friedrichshain is an *Ortsteil*, not a borough. Both venues are on the RAW-Gelände, and nine
-- others at postal code 10245 already carried the borough. Keyed on slug rather than name: a slug is
-- generated once and stable, and 'Der Weiße Hase' would need its encoding to survive this file.
UPDATE venue SET district = 'friedrichshain-kreuzberg'
WHERE slug IN ('crack-bellmer', 'der-weisse-hase')
  AND district = 'friedrichshain';

-- The venue kept its name when it moved and the district followed the name. Am Flutgraben 2 is 41
-- metres from Am Flutgraben 1, which this database already records as Treptow-Köpenick, and postal
-- code 12435 is Alt-Treptow.
UPDATE venue SET district = 'treptow-koepenick'
WHERE slug = 'festsaal-kreuzberg'
  AND district = 'friedrichshain-kreuzberg';

-- The second line, for the reason V006 gives about the licence columns: the application validates
-- through the District enum, and this stops a hand-edited row introducing a thirteenth borough the
-- application would then have to guess about. NULL stays allowed and means nobody recorded one.
--
-- All twelve are listed rather than the ten in use, because a venue in Reinickendorf, Steglitz-
-- Zehlendorf or the two missing boroughs is a venue we have not added yet, not an invalid value.
ALTER TABLE venue
    ADD CONSTRAINT venue_district_valid
        CHECK (district IS NULL OR district IN (
            'charlottenburg-wilmersdorf',
            'friedrichshain-kreuzberg',
            'lichtenberg',
            'marzahn-hellersdorf',
            'mitte',
            'neukoelln',
            'pankow',
            'reinickendorf',
            'spandau',
            'steglitz-zehlendorf',
            'tempelhof-schoeneberg',
            'treptow-koepenick'
        ));
