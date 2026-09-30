-- Whether the importer left out a description or an image because the source's licence prohibits it (#2130).
--
-- Since #807 a prohibited field is never stored, so the read path can no longer tell "the venue wrote
-- nothing" from "the venue wrote something and we withheld it". Both were a null, and the API said
-- `descriptionWithheld: false` for both. #811 wants the notice only where something was taken away,
-- so the importer now records that fact beside the null. A boolean is a fact about the source, not a
-- copy of its text or its image.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).
ALTER TABLE event
    ADD COLUMN description_withheld BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN image_withheld       BOOLEAN NOT NULL DEFAULT FALSE;

-- The rows a prohibition already cleared. Whether each one had a description or an image is no
-- longer known; the clearing ran because the source publishes both, so true is the likely answer.
-- The next import rewrites every upcoming row with what the page actually carries.
UPDATE event e
SET description_withheld = TRUE
FROM event_source s
WHERE e.event_source_id = s.id
  AND s.description_licence = 'PROHIBITED';

UPDATE event e
SET image_withheld = TRUE
FROM event_source s
WHERE e.event_source_id = s.id
  AND s.image_licence = 'PROHIBITED';
