-- A backtick or an acute accent typed as an apostrophe is now folded to `'` in titles, artist and
-- promoter names (#2174): `Dingo`s Dream`, `pris´n break`, `Luv`n Musiq`. The slugger turns all
-- three characters into `-`, so every slug stays, the import reuses each row, and the name it
-- minted stays. There is no re-seed on staging or production, so the rows are renamed here.
--
-- Only between two letters, as the importer folds it. Lookarounds, not captured letters, so both
-- marks of `Rock`n`Roll` fold: a captured `n` would be spent on the first. Production on 2026-09-30: 8 titles, 6 artists,
-- 1 promoter.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

UPDATE artist
SET name = regexp_replace(name, '(?<=[[:alpha:]])[`´](?=[[:alpha:]])', '''', 'g')
WHERE name ~ '[[:alpha:]][`´][[:alpha:]]';

UPDATE promoter
SET name = regexp_replace(name, '(?<=[[:alpha:]])[`´](?=[[:alpha:]])', '''', 'g')
WHERE name ~ '[[:alpha:]][`´][[:alpha:]]';

UPDATE event
SET title = regexp_replace(title, '(?<=[[:alpha:]])[`´](?=[[:alpha:]])', '''', 'g')
WHERE title ~ '[[:alpha:]][`´][[:alpha:]]';
