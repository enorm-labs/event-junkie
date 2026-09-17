-- Tempodrom's `Eiskönigin 1 & 2` was cut at the conjunction, and `2` became an artist row with its
-- own public page (#1556). The split now keeps a number on its show and `isNonArtistName` refuses a
-- digits-only name; this removes the rows that exist, because there is no re-seed on staging or
-- production. Keyed on the slug shape, which no act on either cluster carries, and a no-op where
-- none does. The FK cascade on event_artist removes the link; the event stays and picks up its
-- whole billing on the next import.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

DELETE FROM artist
WHERE slug ~ '^[0-9]+$';
