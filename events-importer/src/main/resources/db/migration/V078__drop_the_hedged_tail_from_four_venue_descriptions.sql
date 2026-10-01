-- Four venue descriptions that ended in a hedged minor format ("the odd concert", "occasional
-- club parties"), rewritten from each venue's own site.
--
-- A tail like that describes nearly any club, so it tells a visitor nothing. Each text now opens
-- with what sets the venue apart and names its formats without the hedge, the rule the
-- scaffold-importer prompt states. Duncker Club also loses its live concerts: its programme lists
-- none, and its own page names only dance nights.
--
-- Every replacement is our own prose, as in V016. **Every UPDATE is guarded on the English wording
-- it replaces**, so a row an operator has edited since keeps its value. Tiffany Club may not be on a
-- cluster yet; its statement then matches no row, and the seed creates it with the new text.
--
-- Unqualified table name, deliberately (ADR-004). Each statement names the source it rests on.

-- Duncker Club
-- source: the venue's own club page: building, architect, club since 1989, Friday, Saturday and Monday dance nights, Montagsduncker since 1993; the programme lists no concert
UPDATE venue SET description = 'A club since 1989 in a listed red-brick building of 1914 by Ludwig Hoffmann in Prenzlauer Berg, with indie and alternative-rock dance nights on Fridays and Saturdays and the dark-wave Montagsduncker every Monday since 1993.', description_alt = 'Seit 1989 ein Club in einem denkmalgeschützten Backsteinbau von Ludwig Hoffmann aus dem Jahr 1914 in Prenzlauer Berg, mit Indie- und Alternative-Rock-Tanznächten am Freitag und Samstag und seit 1993 jeden Montag mit dem Dark-Wave-Abend Montagsduncker.'
WHERE slug = 'duncker-club' AND description = 'A long-running goth, wave and indie club in a historic building in Prenzlauer Berg, best known for its dark-alternative DJ nights and occasional live concerts.';

-- Metropol
-- source: the venue's own history page
UPDATE venue SET description = 'Built in 1906 as a theatre on Nollendorfplatz, later a cinema and a well-known 1980s disco, and now a concert hall for touring acts that also holds club parties.', description_alt = '1906 als Theater am Nollendorfplatz erbaut, später Kino und in den 1980ern eine bekannte Diskothek, heute ein Konzertsaal für Tourneekonzerte, in dem auch Clubpartys stattfinden.'
WHERE slug = 'metropol' AND description = 'A historic concert hall at Nollendorfplatz, programming touring concerts alongside occasional club parties.';

-- Sonnenraum
-- source: the venue's own Sonnenraum page, which names parties and jazz concerts
UPDATE venue SET description = 'Club der Visionäre''s indoor concert room, home to a resident live-band night every Monday, with jazz concerts and label parties on other nights.', description_alt = 'Der Innenkonzertraum des Club der Visionäre, Heimat einer Livebandnacht an jedem Montag, an anderen Abenden mit Jazzkonzerten und Labelpartys.'
WHERE slug = 'sonnenraum' AND description = 'The indoor concert room run by Club der Visionäre, hosting the resident Monday live-band night alongside occasional jazz concerts and label parties.';

-- Tiffany Club
-- source: the venue's own home page and its programme
UPDATE venue SET description = 'A club behind Unter den Linden, five minutes from the Brandenburg Gate, with student, Latin and K-pop party nights alongside stand-up comedy and concerts.', description_alt = 'Ein Club hinter Unter den Linden, fünf Minuten vom Brandenburger Tor, mit Studenten-, Latin- und K-Pop-Partys sowie Stand-up-Comedy und Konzerten.'
WHERE slug = 'tiffany-club' AND description = 'A club and event space just off Unter den Linden, with student, Latin and K-pop party nights, stand-up comedy and the odd concert.';
