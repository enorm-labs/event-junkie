-- YAAM's English description named only 2014, its move to Schillingbrücke, which reads as a founding
-- date. The German text already says YAAM has existed since 1994. The English text now names both.
--
-- Our own prose, as in V078. **The UPDATE is guarded on the English wording it replaces**, so a row an
-- operator has edited since keeps its value. The German text stays.
--
-- Unqualified table name, deliberately (ADR-004).

-- YAAM
-- source: the venue's own page https://yaam.de/das-yaam/: "existiert in Berlin seit 1994", "seit 2014 mit Standort an der Schillingbrücke"
UPDATE venue SET description = 'A non-profit socio-cultural project run by Kult e.V. since 1994, on an 8,000 m² riverside site at Schillingbrücke since 2014, with open-air reggae and hip-hop DJ days, festivals and evening events in its Cool Runnings clubhouse.'
WHERE slug = 'yaam' AND description = 'A non-profit socio-cultural project on an 8,000 m² riverside site at Schillingbrücke since 2014, run by Kult e.V., with open-air reggae and hip-hop DJ days, festivals and evening events in its Cool Runnings clubhouse.';
