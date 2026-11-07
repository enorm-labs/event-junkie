-- V231 — the U-Bahn, S-Bahn and tram stations near a venue (#2724).
--
-- One row per station of VBB's GTFS feed (CC BY 4.0) that a U-Bahn, S-Bahn or tram line serves. `lines` names those
-- lines, S-Bahn first, then U-Bahn, then tram. The importer's quarterly job replaces every row in one transaction; the
-- BFF reads the nearest stations of a venue at request time, so a venue added between two imports needs no backfill.
-- No foreign key: no other table refers to a station. No index on the position: about 1,000 rows read whole is fast enough.

CREATE TABLE transit_stop
(
    gtfs_stop_id TEXT PRIMARY KEY,
    name         TEXT          NOT NULL CHECK (length(name) > 0),
    latitude     NUMERIC(9, 6) NOT NULL CHECK (latitude BETWEEN -90 AND 90),
    longitude    NUMERIC(9, 6) NOT NULL CHECK (longitude BETWEEN -180 AND 180),
    lines        TEXT[]        NOT NULL CHECK (cardinality(lines) > 0),
    imported_at  TIMESTAMPTZ   NOT NULL
);
