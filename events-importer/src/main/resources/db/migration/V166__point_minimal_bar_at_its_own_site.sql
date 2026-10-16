-- Minimal Bar has its own site, minimal.berlin, and an importer reads its programme. The cluster rows
-- still name birgit.club as the website and an RA club page as the programme link. The description now
-- follows the venue's About page.
--
-- Every UPDATE is guarded on the value it replaces, so a row an operator has edited since keeps its value.
--
-- Unqualified table name, deliberately (ADR-004).

UPDATE venue SET website_url = 'https://minimal.berlin/'
WHERE slug = 'minimal-bar' AND website_url = 'https://birgit.club/minimal';

-- The programme link is for a venue we do not import (#2766). The source replaces it.
UPDATE venue SET programme_url = NULL
WHERE slug = 'minimal-bar' AND programme_url = 'https://ra.co/clubs/23370';

-- source: the venue's own page https://minimal.berlin/about/: "five rooms, good drinks, and a DJ playing every night",
-- "free entrance", "an art space, and a table-tennis table in the back room", "house, minimal and dub techno"
UPDATE venue SET description = 'A five-room bar on Rigaer Straße in Friedrichshain with a DJ every night playing house, minimal and dub techno, free entrance, an art space and a table-tennis table in the back room.'
WHERE slug = 'minimal-bar'
  AND description = 'A bar on Rigaer Straße in northern Friedrichshain with a DJ every night of the week, mostly Berlin residents playing minimal and deep house, and table tennis in the back rooms.';

UPDATE venue SET description_alt = 'Eine Bar mit fünf Räumen an der Rigaer Straße in Friedrichshain, in der jeden Abend ein DJ House, Minimal und Dub Techno auflegt, bei freiem Eintritt, mit Kunstraum und Tischtennisplatte im Hinterzimmer.'
WHERE slug = 'minimal-bar'
  AND description_alt = 'Eine Bar an der Rigaer Straße im nördlichen Friedrichshain, in der jeden Abend ein DJ auflegt, meist Minimal und Deep House von Berliner Künstlern, dazu Tischtennis in den Nebenräumen.';
