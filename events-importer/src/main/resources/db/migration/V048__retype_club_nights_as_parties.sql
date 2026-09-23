-- CLUB_NIGHT leaves EventType (#1783). Only migas set it, for its `playing` nights, and every
-- other club night is PARTY. The importer now maps `playing` to PARTY; this retypes the rows that
-- exist, because there is no re-seed on staging or production. A no-op where none is left.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

UPDATE event
SET event_type = 'PARTY'
WHERE event_type = 'CLUB_NIGHT';
