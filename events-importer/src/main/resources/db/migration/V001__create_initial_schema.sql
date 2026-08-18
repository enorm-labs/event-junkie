-- V1: Initial data model for event-junkie
-- Designed to capture music event data from Berlin venue websites
-- (e.g. Astra Kulturhaus, Badehaus Berlin, Cassiopeia, Privatclub).
--
-- Schema creation and search_path are managed by Flyway via spring.flyway.schemas
-- (configured in application.yaml). Tables are intentionally unqualified so the
-- target schema remains configurable without hardcoding it in migration SQL.

-- ============================================================
-- VENUES
-- Represents a physical location where events take place.
-- ============================================================
CREATE TABLE venue
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT        NOT NULL,
    slug        TEXT        NOT NULL UNIQUE,
    address     TEXT,
    city        TEXT        NOT NULL DEFAULT 'Berlin',
    postal_code TEXT,
    -- Berlin borough (Bezirk) as a canonical slug, e.g. 'friedrichshain-kreuzberg'. Set explicitly
    -- per venue; Berlin postal codes map to boroughs many-to-one so PLZ can't derive it reliably.
    district    TEXT,
    latitude    DECIMAL(9, 6),
    longitude   DECIMAL(9, 6),
    website_url TEXT,
    image_url   TEXT,
    -- Short prose description of the venue, shown on the public detail page.
    description TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_venue_district ON venue (district);

-- No explicit slug index needed — the UNIQUE constraint already creates an implicit index.

-- ============================================================
-- ARTISTS
-- Represents a musical artist or band that performs at events.
-- ============================================================
CREATE TABLE artist
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name          TEXT        NOT NULL,
    slug          TEXT        NOT NULL UNIQUE,
    description   TEXT,
    image_url     TEXT,
    website_url   TEXT,
    facebook_url  TEXT,
    instagram_url TEXT,
    youtube_url   TEXT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- No explicit slug index needed — the UNIQUE constraint already creates an implicit index.

-- ============================================================
-- PROMOTERS
-- Represents an event promoter / presenter (e.g. "36 Concerts").
-- ============================================================
CREATE TABLE promoter
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        TEXT        NOT NULL,
    slug        TEXT        NOT NULL UNIQUE,
    website_url TEXT,
    image_url   TEXT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- No explicit slug index needed — the UNIQUE constraint already creates an implicit index.

-- ============================================================
-- EVENT_SOURCE
-- Tracks per-venue import configuration and metadata for the
-- web scraping infrastructure (see ADR-007, ADR-008).
-- Stores the URL to scrape, conditional-request headers
-- (ETag, Last-Modified), scheduling interval, retry state,
-- import status, and result metrics.
-- Defined before EVENT because events reference event_source via FK.
-- ============================================================
CREATE TABLE event_source
(
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id                BIGINT      NOT NULL REFERENCES venue (id),
    name                    TEXT        NOT NULL,
    slug                    TEXT        NOT NULL UNIQUE,
    url                     TEXT        NOT NULL,
    source_type             TEXT        NOT NULL,
    enabled                 BOOLEAN     NOT NULL DEFAULT TRUE,
    import_interval_minutes INT         NOT NULL DEFAULT 1440,
    retry_count             INT         NOT NULL DEFAULT 0,
    max_retries             INT         NOT NULL DEFAULT 3,
    etag                    TEXT,
    last_modified           TEXT,
    last_import_at          TIMESTAMPTZ,
    last_event_count        INT,
    last_error              TEXT,
    status                  TEXT        NOT NULL DEFAULT 'IDLE',
    version                 BIGINT      NOT NULL DEFAULT 0,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_event_source_venue_id ON event_source (venue_id);
-- Covers findDueForImport and findStuckSources query patterns
CREATE INDEX idx_event_source_scheduling ON event_source (enabled, status, last_import_at);

-- ============================================================
-- EVENTS
-- Core entity: a single event at a venue on a specific date.
-- source_id uniquely identifies the event from its import source
-- to enable idempotent imports (upsert).
-- event_source_id links to the event_source that imported this
-- event (nullable — manually created events have no source).
-- ON DELETE SET NULL: deleting an event source orphans its events
-- rather than cascading the delete, preserving event history.
-- Orphaned events (event_source_id = NULL) are no longer cleaned
-- up by any source's stale-event removal and effectively become
-- manually created events.
-- ============================================================
CREATE TABLE event
(
    id                 BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id           BIGINT      NOT NULL REFERENCES venue (id),
    event_source_id    BIGINT      REFERENCES event_source (id) ON DELETE SET NULL,
    title              TEXT        NOT NULL,
    subtitle           TEXT,
    description        TEXT,
    event_type         TEXT        NOT NULL DEFAULT 'CONCERT',
    status             TEXT        NOT NULL DEFAULT 'SCHEDULED',
    slug               TEXT        NOT NULL UNIQUE,
    event_date         DATE        NOT NULL,
    doors_time         TIME,
    start_time         TIME,
    image_url          TEXT,
    source_url         TEXT,
    source_id          TEXT        NOT NULL UNIQUE,
    ticket_url         TEXT,
    facebook_event_url TEXT,
    genre              TEXT,
    price_presale      DECIMAL(10, 2),
    price_box_office   DECIMAL(10, 2),
    price_currency     TEXT        NOT NULL DEFAULT 'EUR',
    price_note         TEXT,
    sold_out           BOOLEAN     NOT NULL DEFAULT FALSE,
    free               BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_event_venue_id ON event (venue_id);
CREATE INDEX idx_event_event_source_id ON event (event_source_id);
CREATE INDEX idx_event_event_date ON event (event_date);
-- No explicit slug index needed — the UNIQUE constraint already creates an implicit index.

-- ============================================================
-- GENRE_TAG
-- Normalized genre labels for structured filtering.
-- Raw genre text is preserved on the event for display;
-- these tags enable frontend filtering and search.
-- ============================================================
CREATE TABLE genre_tag
(
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name       TEXT        NOT NULL,
    slug       TEXT        NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- No explicit slug index needed — the UNIQUE constraint already creates an implicit index.

-- ============================================================
-- EVENT_GENRE_TAG (join table)
-- Links events to their normalized genre tags (many-to-many).
-- ============================================================
CREATE TABLE event_genre_tag
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id     BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    genre_tag_id BIGINT NOT NULL REFERENCES genre_tag (id) ON DELETE CASCADE,
    UNIQUE (event_id, genre_tag_id)
);

CREATE INDEX idx_event_genre_tag_event_id ON event_genre_tag (event_id);
CREATE INDEX idx_event_genre_tag_genre_tag_id ON event_genre_tag (genre_tag_id);

-- ============================================================
-- EVENT_ARTIST (join table)
-- Links events to artists with role, billing order, and stage.
-- `stage` is the room/floor the act plays (e.g. "Panorama Bar") — a per-appearance
-- attribute for multi-room venues (Berghain) and multi-stage festivals; nullable, so
-- single-room venues leave it unset.
-- ============================================================
CREATE TABLE event_artist
(
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id      BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    artist_id     BIGINT NOT NULL REFERENCES artist (id) ON DELETE CASCADE,
    role          TEXT   NOT NULL DEFAULT 'HEADLINER',
    billing_order INT    NOT NULL DEFAULT 0,
    stage         TEXT,
    UNIQUE (event_id, artist_id)
);

CREATE INDEX idx_event_artist_event_id ON event_artist (event_id);
CREATE INDEX idx_event_artist_artist_id ON event_artist (artist_id);

-- ============================================================
-- EVENT_PROMOTER (join table)
-- Links events to their promoters/presenters.
-- ============================================================
CREATE TABLE event_promoter
(
    id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id    BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    promoter_id BIGINT NOT NULL REFERENCES promoter (id) ON DELETE CASCADE,
    UNIQUE (event_id, promoter_id)
);

CREATE INDEX idx_event_promoter_promoter_id ON event_promoter (promoter_id);

-- ============================================================
-- TABLE: data_quality_snapshot — one row per (source, metric, day)
--
-- Pillar 1 of DATA_QUALITY_STRATEGY.md is Measure, and the reason it comes
-- first is that without it every later pillar is judged on whether it *feels*
-- like it helped. A live report cannot do that: it only ever describes today,
-- so there is nothing for a fix to be measured against.
--
-- Append-only and keyed by day rather than updated in place. A row that is
-- overwritten cannot show a regression, and the whole point of the table is
-- that somebody can look back and see when a number started moving.
--
-- `source_slug` is denormalised rather than an FK, deliberately: history has to
-- survive the source being deleted, and `event.event_source_id` is already
-- ON DELETE SET NULL — so a foreign key here would silently take the history
-- with it exactly when somebody is asking what happened. 'manual' is the
-- synthetic bucket for events with no source, so nothing is silently excluded.
-- ============================================================
CREATE TABLE data_quality_snapshot
(
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The day the snapshot describes, not the instant it was written: the
    -- scheduled writer's clock drifting past midnight must not create a second
    -- row for the same day, which is what the unique constraint below enforces.
    snapshot_date DATE        NOT NULL,
    source_slug   TEXT        NOT NULL,
    metric        TEXT        NOT NULL,
    -- Both numbers, not a ratio. A percentage cannot be re-aggregated across
    -- sources or days without the denominator, and the denominator is itself a
    -- signal — 40% of 5 events and 40% of 500 are different findings.
    metric_count  BIGINT      NOT NULL,
    total_events  BIGINT      NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (snapshot_date, source_slug, metric)
);

-- The query every dashboard runs: one metric for one source over time.
CREATE INDEX idx_data_quality_snapshot_series ON data_quality_snapshot (source_slug, metric, snapshot_date);

-- ============================================================
-- TRIGGER: auto-update updated_at on row modification
-- A database-level trigger is more reliable than application-level
-- auditing since it catches all update paths (including raw SQL).
-- ============================================================
CREATE OR REPLACE FUNCTION set_updated_at()
    RETURNS TRIGGER AS
$$
BEGIN
    NEW.updated_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_venue_updated_at
    BEFORE UPDATE
    ON venue
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_artist_updated_at
    BEFORE UPDATE
    ON artist
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_promoter_updated_at
    BEFORE UPDATE
    ON promoter
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_event_updated_at
    BEFORE UPDATE
    ON event
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();


CREATE TRIGGER trg_genre_tag_updated_at
    BEFORE UPDATE
    ON genre_tag
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();

CREATE TRIGGER trg_event_source_updated_at
    BEFORE UPDATE
    ON event_source
    FOR EACH ROW
EXECUTE FUNCTION set_updated_at();
