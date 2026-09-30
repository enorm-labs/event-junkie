-- Gretchen wrote the cancellation notice of Mop Mop ft. Anthony Joseph as a second lineup block, and
-- three of its sentences were stored as support acts (#2167). The scraper now reads a block that holds
-- a sentence as a notice; this removes the three rows, because there is no re-seed on staging or
-- production. It follows V064.
--
-- A row is deleted only when every event it bills is a Gretchen event, so a same-slug row from
-- another venue stays. The FK cascade on event_artist removes the links.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist a
WHERE a.slug IN (
    'tickets-konnen-da-zuruckgegeben-werden-wo-sie-gekauft-wurden',
    'wir-suchen-einen-neuen-termin-um-das-konzert-nachzuholen',
    'es-tut-uns-sehr-leid'
)
  AND NOT EXISTS (
      SELECT 1
      FROM event_artist ea
      JOIN event e ON e.id = ea.event_id
      WHERE ea.artist_id = a.id
        AND e.source_id NOT LIKE 'gretchen:%'
  );
