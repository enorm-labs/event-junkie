-- The language a show is performed in, where the source states it (#2523).
--
-- `spoken_languages` is what is said on stage or on screen, one ISO 639-1 code per language: `{en}` for
-- English stand-up, `{de,en}` for a bilingual open mic. It is not `description_language`, which is the
-- language of the blurb: a German blurb for an English show is common. NULL means the source did not say,
-- never "German by default". An empty array is refused so that unknown has one spelling.
--
-- `subtitle_language` is the subtitles of a screening shown in the original: `de` for OmU, `en` for OmeU.
--
-- No backfill: the next import writes both, because change detection sees the new values.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE event
    ADD COLUMN spoken_languages TEXT[],
    ADD COLUMN subtitle_language TEXT,
    ADD CONSTRAINT event_spoken_languages_valid CHECK (
        spoken_languages IS NULL
            OR (cardinality(spoken_languages) > 0
                AND spoken_languages <@ ARRAY['de', 'en', 'fr', 'es', 'it', 'tr', 'ru', 'pl']::TEXT[])
    ),
    ADD CONSTRAINT event_subtitle_language_valid CHECK (
        subtitle_language IS NULL OR subtitle_language IN ('de', 'en', 'fr', 'es', 'it', 'tr', 'ru', 'pl')
    );
