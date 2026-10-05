-- The description a translation engine refused, by its hash, so the next import does not buy the same refusal again (#2714).
--
-- A refusal leaves `description_alt` NULL, and the translation pass read NULL as "not yet translated". Every import sent the
-- same text again and paid for the same refusal: 100 requests for 14 events in ten days. The hash keys the refusal to the
-- text, so an edited description is tried once more.
--
-- Its own column rather than `description_alt_source_hash` without a text: that column means "the alt text was made from
-- this", and a hash with no alt text would make it mean two things.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` before running this (ADR-004).

ALTER TABLE event
    ADD COLUMN description_alt_refused_hash TEXT;
