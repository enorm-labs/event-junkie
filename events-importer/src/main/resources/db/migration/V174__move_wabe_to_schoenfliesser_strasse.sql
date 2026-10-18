-- WABE's hall in Ernst-Thälmann-Park is closed for renovation. Since 2026-05-01 it plays at Schönfließer
-- Straße 7, so the map and the address sent visitors to a closed building. The description already names
-- the interim address; the address, postal code and point now agree with it.
--
-- source: the venue's own page https://www.wabe-berlin.info/: "Wir sind nun in der schönfließer straße 7
-- für sie da!", and every event there is at "Schönfließer Straße 7 (Saal)".
-- The point is OpenStreetMap's (ODbL) for the hall at that address, which Theater unterm Dach shares.
--
-- The UPDATE is guarded on the address and postal code it replaces, so a row an operator has edited since
-- keeps its values. When WABE moves back, a later migration reverses this one.
--
-- Unqualified table name, deliberately (ADR-004).

UPDATE venue SET address = 'Schönfließer Straße 7', postal_code = '10439',
                 latitude = 52.553381, longitude = 13.408873
WHERE slug = 'wabe' AND address = 'Danziger Straße 101' AND postal_code = '10405';
