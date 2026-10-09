-- The monthly site check of venues without an importer (#2812). For an imported venue a failing import reveals a
-- closure. For a venue with no `event_source` nothing does, so a closed club would stay listed with a dead link.
--
-- One row per venue, overwritten by each pass: the last outcome and the run of failures, not a history.
--
-- An automated signal only puts a venue up for review. A dead site is not a closed venue: RSO's domain answered 404 and
-- Funkloch's did not resolve while both were open. Nothing here writes `venue.closed_on` or `venue.reviewed_at`; a person
-- sets both through the admin API (ADR-046).
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).
CREATE TABLE venue_site_check
(
    venue_id             BIGINT PRIMARY KEY REFERENCES venue (id) ON DELETE CASCADE,
    checked_at           TIMESTAMPTZ NOT NULL,
    -- The URL whose answer decided the outcome: the first that answered, or the first that failed when none did.
    url                  TEXT,
    -- OK, HTTP, DNS, TLS, TIMEOUT, CONNECTION or OTHER. SKIPPED when no URL gave an answer about the site: none set,
    -- a host whose terms forbid automated access, a robots.txt disallow, or a 403 or 429. A skip leaves the failure
    -- run as it was.
    outcome              TEXT        NOT NULL,
    -- The status of an HTTP outcome, or the 403 or 429 of a SKIPPED one.
    http_status          INTEGER,
    consecutive_failures INTEGER     NOT NULL DEFAULT 0,
    -- When the current run of failures began. NULL while the site answers.
    failing_since        TIMESTAMPTZ,
    CONSTRAINT venue_site_check_outcome CHECK (outcome IN ('OK', 'HTTP', 'DNS', 'TLS', 'TIMEOUT', 'CONNECTION', 'OTHER', 'SKIPPED'))
);
