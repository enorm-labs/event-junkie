-- V060 — the running order: when each act plays at this event (#2002)
--
-- Instants, not local times, because a club night's slots cross midnight and a Klubnacht runs into
-- Monday: the event's date says nothing about which day a 04:30 set falls on. Both nullable — most
-- venues publish neither, some only the start. No CHECK on the order: one venue typo would fail the
-- batch and roll back the whole run.

ALTER TABLE event_artist
    ADD COLUMN set_start TIMESTAMPTZ,
    ADD COLUMN set_end   TIMESTAMPTZ;
