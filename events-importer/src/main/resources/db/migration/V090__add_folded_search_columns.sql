-- The folded text the public search compares with (#2428). TextSearch.kt in events-bff holds the
-- matching rule; this file holds the folding.
--
-- `search_norm` folds the visitor's term: `ÆDEN` is `aeden`, `Straße` is `strasse`, `Neukölln` is
-- `neukolln`, `Kit Kat Club!` is `kit kat club`. `search_text` folds a stored name twice and keeps
-- both when they differ: `Neukölln` is `neukolln | neukoelln`. So `neukolln` and `neukoelln` both
-- find it, while `aeden` stays out of `Schokoladen`, as folding a typed `ae` to `a` would not.
--
-- Each searched column has a STORED twin `<column>_search` from `search_text`. Folding on read cost
-- 46-55 ms per event query on a copy of staging, the stored twin 11 ms; the old `ILIKE` took 3 ms.
-- No trigram index: the similarity pass calls `word_similarity`, which no index serves.
--
-- Both extensions are trusted, so the database owner can create them without superuser. The
-- `events` role owns the database on both clusters (CLUSTER_BOOTSTRAP.md §8).
--
-- `BEGIN ATOMIC` resolves every name when the function is created, so the functions work under any
-- `search_path`, including a `pg_dump` restore's. `unaccent(text)` alone is STABLE because it looks
-- the dictionary up by name; the two-argument form with a resolved dictionary makes IMMUTABLE true.
-- `COLLATE pg_c_utf8` makes `lower` and `[:alnum:]` Unicode-aware under any database collation;
-- under `C` both are ASCII-only, and `Кино` would fold to nothing.
--
-- Unqualified names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` before
-- running this (ADR-004), so both extensions land in the `events` schema.

CREATE EXTENSION IF NOT EXISTS unaccent;
CREATE EXTENSION IF NOT EXISTS pg_trgm;

CREATE FUNCTION search_norm(input TEXT) RETURNS TEXT
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    STRICT
BEGIN ATOMIC
    SELECT trim(
        regexp_replace(
            lower(unaccent('unaccent'::regdictionary, normalize(input, NFC)) COLLATE pg_c_utf8),
            '[^[:alnum:]]+', ' ', 'g'
        )
    );
END;

CREATE FUNCTION search_text(input TEXT) RETURNS TEXT
    LANGUAGE sql
    IMMUTABLE
    PARALLEL SAFE
    STRICT
BEGIN ATOMIC
    SELECT CASE WHEN plain = german THEN plain ELSE plain || ' | ' || german END
    FROM (
        SELECT
            search_norm(input) AS plain,
            search_norm(
                replace(replace(replace(replace(replace(replace(
                    normalize(input, NFC), 'ä', 'ae'), 'ö', 'oe'), 'ü', 'ue'), 'Ä', 'Ae'), 'Ö', 'Oe'), 'Ü', 'Ue')
            ) AS german
    ) AS folded;
END;

ALTER TABLE event
    ADD COLUMN title_search TEXT GENERATED ALWAYS AS (search_text(title)) STORED,
    ADD COLUMN subtitle_search TEXT GENERATED ALWAYS AS (search_text(subtitle)) STORED;
ALTER TABLE venue ADD COLUMN name_search TEXT GENERATED ALWAYS AS (search_text(name)) STORED;
ALTER TABLE artist ADD COLUMN name_search TEXT GENERATED ALWAYS AS (search_text(name)) STORED;
ALTER TABLE promoter ADD COLUMN name_search TEXT GENERATED ALWAYS AS (search_text(name)) STORED;
