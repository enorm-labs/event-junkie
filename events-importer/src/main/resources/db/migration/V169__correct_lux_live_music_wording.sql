-- L.U.X's descriptions said it has no live music "by design" ("bewusst"). Its own site says the opposite
-- of a choice: live music is not possible there, and the bar regrets it. Both texts now say so.
--
-- Our own prose, as in V162. **Each UPDATE is guarded on the wording it replaces**, so a row an operator
-- has edited since keeps its value.
--
-- Unqualified table name, deliberately (ADR-004).

-- L.U.X
-- source: the venue's own page https://www.lux-berlin.net/: "LiveMusik ist in der L.U.X nicht möglich - finden wir auch sehr schade, ist aber so."
UPDATE venue SET description = 'A Kreuzberg bar on Schlesische Straße where vinyl DJs play, with free entry on many Thursdays. Live music is not possible in the room.'
WHERE slug = 'l-u-x' AND description = 'A Kreuzberg bar on Schlesische Straße that has no live music by design: vinyl DJs play, with free entry on many Thursdays.';

UPDATE venue SET description_alt = 'Bar in der Schlesischen Straße, in der Schallplatten-DJs auflegen, donnerstags oft bei freiem Eintritt. Livemusik ist in der L.U.X nicht möglich.'
WHERE slug = 'l-u-x' AND description_alt = 'Bar in der Schlesischen Straße, in der es bewusst keine Livemusik gibt: Hier legen Schallplatten-DJs auf, donnerstags oft bei freiem Eintritt.';
