-- ufaFabrik's genre label names a format more often than a style, and the importer stored it as the
-- genre (#3107). It now keeps the label only when it names a style. The next import drops the
-- links of upcoming rows, but `/api/genres` lists every `genre_tag` row, so these 14 format tags
-- stay public until deleted here. On production on 2026-10-11 only ufaFabrik events carried them.
-- `poetry` stays: "Jazz & Poetry" names a style, so the importer still writes it.
--
-- `event_genre_tag` follows the tag by `ON DELETE CASCADE`.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM genre_tag
WHERE slug IN (
    'theater',
    'musik',
    'mitsing-abend',
    'leseshow',
    'live-horspiel',
    'zauberei',
    'mentalmagie',
    'puppenspiel',
    'clowneskes-theater',
    'comedy-theater',
    'improvisationstheater',
    'orientalische-tanzshow',
    'filmpremiere',
    'crossover-performance'
);
