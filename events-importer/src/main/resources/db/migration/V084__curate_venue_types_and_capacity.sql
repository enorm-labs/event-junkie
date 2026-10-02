-- Venue types and capacity for every venue on the seed, curated by hand (#327, #369).
--
-- Types come from each venue's own site. A capacity comes only from the venue, its operator or an
-- official city page, and each statement names that source; a venue without one keeps NULL.
--
-- Every UPDATE is guarded on `venue_types = '{}'`, so a venue an operator has typed since keeps its
-- values. A slug a cluster does not hold matches no row, and the seed creates that venue typed.
--
-- Unqualified table name, deliberately (ADR-004).

-- A-Trane
-- capacity: https://www.visitberlin.de/en/trane-jazz-club
UPDATE venue SET venue_types = '{live-venue}', capacity = 100 WHERE slug = 'a-trane' AND venue_types = '{}';

-- Abstand
UPDATE venue SET venue_types = '{bar}' WHERE slug = 'abstand' AND venue_types = '{}';

-- Admiralspalast
-- capacity: https://www.admiralspalast.theater/files/simpler/images-ap/pdf/Admiralspalast_Eventbroschuere_DE_2025_.pdf
UPDATE venue SET venue_types = '{theatre,live-venue}', capacity = 1737 WHERE slug = 'admiralspalast' AND venue_types = '{}';

-- ÆDEN
UPDATE venue SET venue_types = '{club,open-air}' WHERE slug = 'aeden' AND venue_types = '{}';

-- Alte Kantine
-- capacity: https://kulturbrauerei.de/angebote/alte-kantine/
UPDATE venue SET venue_types = '{club,live-venue}', capacity = 450 WHERE slug = 'alte-kantine' AND venue_types = '{}';

-- AMT
UPDATE venue SET venue_types = '{club}' WHERE slug = 'amt' AND venue_types = '{}';

-- Arcanoa
UPDATE venue SET venue_types = '{bar}' WHERE slug = 'arcanoa' AND venue_types = '{}';

-- ART Stalker
UPDATE venue SET venue_types = '{bar,gallery}' WHERE slug = 'art-stalker' AND venue_types = '{}';

-- Astra Kulturhaus
UPDATE venue SET venue_types = '{live-venue}' WHERE slug = 'astra-kulturhaus' AND venue_types = '{}';

-- Badehaus
-- capacity: https://badehaus-berlin.com/en/veranstalter/
UPDATE venue SET venue_types = '{live-venue,club}', capacity = 450 WHERE slug = 'badehaus' AND venue_types = '{}';

-- Bar jeder Vernunft
-- capacity: https://www.bar-jeder-vernunft.de/en/eventlocation-berlin.html
UPDATE venue SET venue_types = '{theatre}', capacity = 300 WHERE slug = 'bar-jeder-vernunft' AND venue_types = '{}';

-- Berghain / Panorama Bar
UPDATE venue SET venue_types = '{club}' WHERE slug = 'berghain-panorama-bar' AND venue_types = '{}';

-- Bi Nuu
-- capacity: https://binuu.de/de/location
UPDATE venue SET venue_types = '{live-venue,club}', capacity = 500 WHERE slug = 'bi-nuu' AND venue_types = '{}';

-- Cassiopeia
-- capacity: https://cassiopeia-berlin.de/eventlocation
UPDATE venue SET venue_types = '{club,live-venue}', capacity = 700 WHERE slug = 'cassiopeia' AND venue_types = '{}';

-- Clash
UPDATE venue SET venue_types = '{bar,live-venue}' WHERE slug = 'clash' AND venue_types = '{}';

-- Club der Visionäre
UPDATE venue SET venue_types = '{club,open-air}' WHERE slug = 'club-der-visionare' AND venue_types = '{}';

-- Club OST
UPDATE venue SET venue_types = '{club}' WHERE slug = 'club-ost' AND venue_types = '{}';

-- Colosseum
UPDATE venue SET venue_types = '{cinema}' WHERE slug = 'colosseum' AND venue_types = '{}';

-- Columbia Theater
UPDATE venue SET venue_types = '{live-venue}' WHERE slug = 'columbia-theater' AND venue_types = '{}';

-- Columbiahalle
-- capacity: https://www.columbiahalle.berlin/ueber-uns.html
UPDATE venue SET venue_types = '{live-venue}', capacity = 3500 WHERE slug = 'columbiahalle' AND venue_types = '{}';

-- Cosmic Comedy Club
UPDATE venue SET venue_types = '{theatre}' WHERE slug = 'cosmic-comedy-club' AND venue_types = '{}';

-- Crack Bellmer
UPDATE venue SET venue_types = '{club,bar}' WHERE slug = 'crack-bellmer' AND venue_types = '{}';

-- Der Weiße Hase
UPDATE venue SET venue_types = '{club}' WHERE slug = 'der-weisse-hase' AND venue_types = '{}';

-- Drugstore
UPDATE venue SET venue_types = '{cultural-centre}' WHERE slug = 'drugstore' AND venue_types = '{}';

-- Duncker Club
-- capacity: https://www.dunckerclub.de/duncker.html
UPDATE venue SET venue_types = '{club}', capacity = 250 WHERE slug = 'duncker-club' AND venue_types = '{}';

-- Eschschloraque Rümschrümp
UPDATE venue SET venue_types = '{bar,club}' WHERE slug = 'eschschloraque-rumschrump' AND venue_types = '{}';

-- Festsaal Kreuzberg
-- capacity: https://festsaal-kreuzberg.de/de/venue/räume/
UPDATE venue SET venue_types = '{live-venue,club}', capacity = 1500 WHERE slug = 'festsaal-kreuzberg' AND venue_types = '{}';

-- Fitzroy
UPDATE venue SET venue_types = '{club,live-venue}' WHERE slug = 'fitzroy' AND venue_types = '{}';

-- Frannz Club
UPDATE venue SET venue_types = '{live-venue,club}' WHERE slug = 'frannz-club' AND venue_types = '{}';

-- gART.n
UPDATE venue SET venue_types = '{open-air,club}' WHERE slug = 'gart-n' AND venue_types = '{}';

-- Gärten der Welt
-- capacity: https://www.gaertenderwelt.de/en/events/open-air-arena/
UPDATE venue SET venue_types = '{open-air}', capacity = 5000 WHERE slug = 'garten-der-welt' AND venue_types = '{}';

-- Golden Gate
UPDATE venue SET venue_types = '{club}' WHERE slug = 'golden-gate' AND venue_types = '{}';

-- Gretchen
UPDATE venue SET venue_types = '{club,live-venue}' WHERE slug = 'gretchen' AND venue_types = '{}';

-- Havanna
UPDATE venue SET venue_types = '{club}' WHERE slug = 'havanna' AND venue_types = '{}';

-- Heideglühen
UPDATE venue SET venue_types = '{club,open-air}' WHERE slug = 'heidegluhen' AND venue_types = '{}';

-- Heimathafen Neukölln
-- capacity: https://heimathafen-neukoelln.de/wp-content/uploads/2019/08/Heimathafen_Vermietung.pdf
UPDATE venue SET venue_types = '{theatre,live-venue}', capacity = 800 WHERE slug = 'heimathafen-neukolln' AND venue_types = '{}';

-- Hole 44
-- capacity: https://channelmusic.de/en/hole44/
UPDATE venue SET venue_types = '{live-venue}', capacity = 650 WHERE slug = 'hole-44' AND venue_types = '{}';

-- Humboldthain Club
UPDATE venue SET venue_types = '{club,open-air}' WHERE slug = 'humboldthain-club' AND venue_types = '{}';

-- Huxleys Neue Welt
-- capacity: https://channelmusic.de/en/huxleysneuewelt/
UPDATE venue SET venue_types = '{live-venue}', capacity = 1600 WHERE slug = 'huxleys-neue-welt' AND venue_types = '{}';

-- Junction Bar
UPDATE venue SET venue_types = '{bar,live-venue}' WHERE slug = 'junction-bar' AND venue_types = '{}';

-- Kantine am Berghain
UPDATE venue SET venue_types = '{live-venue}' WHERE slug = 'kantine-am-berghain' AND venue_types = '{}';

-- Kater
UPDATE venue SET venue_types = '{club,open-air}' WHERE slug = 'kater' AND venue_types = '{}';

-- Klunkerkranich
UPDATE venue SET venue_types = '{open-air,club,bar}' WHERE slug = 'klunkerkranich' AND venue_types = '{}';

-- KØPI
UPDATE venue SET venue_types = '{cultural-centre}' WHERE slug = 'kopi' AND venue_types = '{}';

-- Kulturhaus Insel Berlin
-- capacity: https://www.inselberlin.de/vermietung
UPDATE venue SET venue_types = '{live-venue,open-air,cultural-centre}', capacity = 250 WHERE slug = 'kulturhaus-insel-berlin' AND venue_types = '{}';

-- Kulturhaus Peter Edel
-- capacity: https://www.peteredel.de/mieten/festsaal/
UPDATE venue SET venue_types = '{cultural-centre}', capacity = 350 WHERE slug = 'kulturhaus-peter-edel' AND venue_types = '{}';

-- LARK
UPDATE venue SET venue_types = '{live-venue,club}' WHERE slug = 'lark' AND venue_types = '{}';

-- Lido
UPDATE venue SET venue_types = '{live-venue,club}' WHERE slug = 'lido' AND venue_types = '{}';

-- Loge
-- capacity: https://www.loge-berlin.org/venue-specs
UPDATE venue SET venue_types = '{bar,live-venue}', capacity = 130 WHERE slug = 'loge' AND venue_types = '{}';

-- MAAYA
UPDATE venue SET venue_types = '{cultural-centre,open-air,gallery}' WHERE slug = 'maaya' AND venue_types = '{}';

-- Madame Claude
UPDATE venue SET venue_types = '{bar,live-venue}' WHERE slug = 'madame-claude' AND venue_types = '{}';

-- Matrix
UPDATE venue SET venue_types = '{club}' WHERE slug = 'matrix' AND venue_types = '{}';

-- Max-Schmeling-Halle
-- capacity: https://www.max-schmeling-halle.de/location/daten-fakten
UPDATE venue SET venue_types = '{arena}', capacity = 11900 WHERE slug = 'max-schmeling-halle' AND venue_types = '{}';

-- MAXXIM
UPDATE venue SET venue_types = '{club}' WHERE slug = 'maxxim' AND venue_types = '{}';

-- Metropol
-- capacity: https://convention.visitberlin.de/en/event-planning/berlin-convention-finder/venues/metropol-berlin
UPDATE venue SET venue_types = '{live-venue,club}', capacity = 1298 WHERE slug = 'metropol' AND venue_types = '{}';

-- migas
UPDATE venue SET venue_types = '{bar}' WHERE slug = 'migas' AND venue_types = '{}';

-- Mikropol
-- capacity: https://mikropol-berlin.de
UPDATE venue SET venue_types = '{live-venue,club}', capacity = 250 WHERE slug = 'mikropol' AND venue_types = '{}';

-- Modus Berlin
UPDATE venue SET venue_types = '{live-venue}' WHERE slug = 'modus-berlin' AND venue_types = '{}';

-- Monarch
-- capacity: https://kotti.club/monarch/
UPDATE venue SET venue_types = '{bar,club}', capacity = 200 WHERE slug = 'monarch' AND venue_types = '{}';

-- Monster Ronson's Ichiban Karaoke
-- capacity: https://www.karaokemonster.de/book-the-stage
UPDATE venue SET venue_types = '{bar}', capacity = 75 WHERE slug = 'monster-ronson-s-ichiban-karaoke' AND venue_types = '{}';

-- Morphine Raum
UPDATE venue SET venue_types = '{live-venue}' WHERE slug = 'morphine-raum' AND venue_types = '{}';

-- MS Hoppetosse
UPDATE venue SET venue_types = '{club}' WHERE slug = 'ms-hoppetosse' AND venue_types = '{}';

-- Neue Zukunft
UPDATE venue SET venue_types = '{cinema,bar,live-venue}' WHERE slug = 'neue-zukunft' AND venue_types = '{}';

-- OHM
UPDATE venue SET venue_types = '{club}' WHERE slug = 'ohm' AND venue_types = '{}';

-- Panke Culture
UPDATE venue SET venue_types = '{club,bar,gallery}' WHERE slug = 'panke-culture' AND venue_types = '{}';

-- Parkbühne Wuhlheide
-- capacity: https://www.wuhlheide.de/besucherinfo/faq
UPDATE venue SET venue_types = '{open-air,live-venue}', capacity = 17000 WHERE slug = 'parkbuhne-wuhlheide' AND venue_types = '{}';

-- Privatclub
UPDATE venue SET venue_types = '{live-venue,club}' WHERE slug = 'privatclub' AND venue_types = '{}';

-- Quasimodo
UPDATE venue SET venue_types = '{live-venue,club}' WHERE slug = 'quasimodo' AND venue_types = '{}';

-- Renate
UPDATE venue SET venue_types = '{club,open-air}' WHERE slug = 'renate' AND venue_types = '{}';

-- Ritter Butzke
UPDATE venue SET venue_types = '{club}' WHERE slug = 'ritter-butzke' AND venue_types = '{}';

-- Roadrunner's Paradise
-- capacity: https://www.pankow-weissensee-prenzlauerberg.berlin/en/roadrunners-paradise-club
UPDATE venue SET venue_types = '{club,live-venue}', capacity = 350 WHERE slug = 'roadrunner-s-paradise' AND venue_types = '{}';

-- ROSA
UPDATE venue SET venue_types = '{club}' WHERE slug = 'rosa' AND venue_types = '{}';

-- Säälchen
UPDATE venue SET venue_types = '{live-venue}' WHERE slug = 'saalchen' AND venue_types = '{}';

-- Schokoladen
UPDATE venue SET venue_types = '{live-venue,cultural-centre}' WHERE slug = 'schokoladen' AND venue_types = '{}';

-- silent green
UPDATE venue SET venue_types = '{cultural-centre}' WHERE slug = 'silent-green' AND venue_types = '{}';

-- Sisyphos
UPDATE venue SET venue_types = '{club,open-air}' WHERE slug = 'sisyphos' AND venue_types = '{}';

-- SO36
UPDATE venue SET venue_types = '{club,live-venue}' WHERE slug = 'so36' AND venue_types = '{}';

-- Soda Club
UPDATE venue SET venue_types = '{club}' WHERE slug = 'soda-club' AND venue_types = '{}';

-- Sonnenraum
UPDATE venue SET venue_types = '{live-venue}' WHERE slug = 'sonnenraum' AND venue_types = '{}';

-- Supamolly
UPDATE venue SET venue_types = '{live-venue,bar,cultural-centre}' WHERE slug = 'supamolly' AND venue_types = '{}';

-- Tempodrom
-- capacity: https://www.tempodrom.de/en/location/big-arena/
UPDATE venue SET venue_types = '{arena}', capacity = 4200 WHERE slug = 'tempodrom' AND venue_types = '{}';

-- Theater im Delphi
UPDATE venue SET venue_types = '{theatre,live-venue}' WHERE slug = 'theater-im-delphi' AND venue_types = '{}';

-- Tiffany Club
-- capacity: https://convention.visitberlin.de/en/event-planning/berlin-convention-finder/venues/tiffany-eventlocation
UPDATE venue SET venue_types = '{club}', capacity = 650 WHERE slug = 'tiffany-club' AND venue_types = '{}';

-- Tresor
-- capacity: https://www.bbfc-cloud.de/en/public/locations/tresor-club-berlin
UPDATE venue SET venue_types = '{club}', capacity = 700 WHERE slug = 'tresor' AND venue_types = '{}';

-- Uber Arena
-- capacity: https://www.uber-arena.de/die-arena/daten-fakten
UPDATE venue SET venue_types = '{arena}', capacity = 17000 WHERE slug = 'uber-arena' AND venue_types = '{}';

-- Uber Eats Music Hall
-- capacity: https://www.uber-platz.de/uber-platz/daten-fakten
UPDATE venue SET venue_types = '{arena}', capacity = 4500 WHERE slug = 'uber-eats-music-hall' AND venue_types = '{}';

-- ufaFabrik
-- capacity: https://ufafabrik.de/10394/vermietungen.html
UPDATE venue SET venue_types = '{cultural-centre,theatre}', capacity = 500 WHERE slug = 'ufafabrik' AND venue_types = '{}';

-- UFO im Velodrom
-- capacity: https://www.ufo-velodrom.de/en/location
UPDATE venue SET venue_types = '{live-venue}', capacity = 5000 WHERE slug = 'ufo-im-velodrom' AND venue_types = '{}';

-- Urania
-- capacity: https://www.urania.de/raeume-1/
UPDATE venue SET venue_types = '{theatre}', capacity = 866 WHERE slug = 'urania' AND venue_types = '{}';

-- Urban Spree
UPDATE venue SET venue_types = '{gallery,live-venue}' WHERE slug = 'urban-spree' AND venue_types = '{}';

-- Velodrom
-- capacity: https://www.velodrom.de/en/location
UPDATE venue SET venue_types = '{arena}', capacity = 12000 WHERE slug = 'velodrom' AND venue_types = '{}';

-- VOID Club
UPDATE venue SET venue_types = '{club}' WHERE slug = 'void-club' AND venue_types = '{}';

-- Wild at Heart
UPDATE venue SET venue_types = '{bar,live-venue}' WHERE slug = 'wild-at-heart' AND venue_types = '{}';

-- Zenner
-- capacity: https://zenner.berlin/mieten/
UPDATE venue SET venue_types = '{club,open-air,live-venue}', capacity = 800 WHERE slug = 'zenner' AND venue_types = '{}';

-- Zig Zag Hall
UPDATE venue SET venue_types = '{live-venue}' WHERE slug = 'zig-zag-hall' AND venue_types = '{}';

-- Zig Zag Jazz Club
UPDATE venue SET venue_types = '{live-venue}' WHERE slug = 'zig-zag-jazz-club' AND venue_types = '{}';

-- Zitadelle
UPDATE venue SET venue_types = '{open-air}' WHERE slug = 'zitadelle' AND venue_types = '{}';

-- Zur Klappe
UPDATE venue SET venue_types = '{club}' WHERE slug = 'zur-klappe' AND venue_types = '{}';
