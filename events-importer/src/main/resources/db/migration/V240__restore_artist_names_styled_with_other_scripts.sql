-- De-shouting lowered the Greek letters and symbols that two acts use as Latin look-alikes: Ritter Butzke's
-- KABΛNI became Kabλni, and VOID Club's NΞØN BL△CK became Nξøn Bl△ck. The normalizer now keeps such a word.
-- An import resolves by slug and does not rename an existing row, so the stored names are restored here.
--
-- Keyed on slug and the old name, so a row a person corrected since is left alone.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

UPDATE artist SET name = 'KABΛNI' WHERE slug = 'kabni' AND name = 'Kabλni';
UPDATE artist SET name = 'NΞØN BL△CK' WHERE slug = 'non-blck' AND name = 'Nξøn Bl△ck';
