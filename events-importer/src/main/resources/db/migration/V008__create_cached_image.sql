-- Where a venue image is cached on our own origin, so a visitor's browser never contacts the venue
-- (ADR-019, #792).
--
-- Two tables rather than one. `cached_image` is what we fetched, keyed on the venue's URL.
-- `cached_image_variant` is what we serve, one row per width and format. A single table cannot hold
-- several widths in several formats for one source URL, and flattening them into columns fixes the
-- set of derivatives in the schema.
--
-- `event.image_url` keeps the venue's URL and is not touched. It is the provenance and it is what a
-- refetch needs; the BFF substitutes our URL when it builds a response (ADR-019 §2.3).
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TABLE cached_image
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- The venue's URL, and the natural key. Two events sharing one poster converge on this row, so
    -- the image is fetched once rather than once per event.
    source_url       TEXT        NOT NULL UNIQUE,
    -- SHA-256 of the bytes. Null until a fetch succeeds. It is the storage key from PR 4 onward,
    -- which is what makes an object immutable and safe to cache for a year (ADR-019 §3.1).
    content_hash     TEXT,
    -- Sniffed from the bytes, never copied from the Content-Type header. An SVG served from our own
    -- origin runs script in our origin, so the allow-list is decided by what the file is.
    content_type     TEXT,
    byte_size        BIGINT,
    intrinsic_width  INTEGER,
    intrinsic_height INTEGER,
    -- Conditional-request headers, so a nightly re-import costs a 304 rather than a download.
    etag             TEXT,
    last_modified    TEXT,
    fetched_at       TIMESTAMPTZ,
    last_seen_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- The negative cache. Without it a dead URL is re-fetched every night forever, which is load on
    -- a venue that gets nothing in return.
    failed_at        TIMESTAMPTZ,
    failure_reason   TEXT,
    -- Set by the takedown route rather than by a DELETE, so a removed image is not re-fetched by the
    -- next import that still sees the URL on the page.
    deleted_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- What the scheduler asks for: rows never fetched, or fetched long enough ago to re-check. Partial,
-- because a deleted row is never a candidate and indexing it would be a dead entry.
CREATE INDEX idx_cached_image_due
    ON cached_image (fetched_at, failed_at)
    WHERE deleted_at IS NULL;

-- The orphan sweep's question, asked from the other side: which stored objects does anything still
-- point at. Null hashes are the rows with no object yet, so they are excluded.
CREATE INDEX idx_cached_image_content_hash
    ON cached_image (content_hash)
    WHERE content_hash IS NOT NULL;

CREATE TABLE cached_image_variant
(
    id              BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    cached_image_id BIGINT      NOT NULL REFERENCES cached_image (id) ON DELETE CASCADE,
    width           INTEGER     NOT NULL,
    format          TEXT        NOT NULL,
    -- The object key under the environment's prefix. One bucket serves both environments and the
    -- prefix is derived from `environment`, so staging's sweep cannot reach production's objects
    -- (#270, ADR-019 §2.8).
    storage_key     TEXT        NOT NULL,
    byte_size       BIGINT      NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    -- One row per width and format. A repeated pair is a bug in the generator, not a second file.
    UNIQUE (cached_image_id, width, format)
);
