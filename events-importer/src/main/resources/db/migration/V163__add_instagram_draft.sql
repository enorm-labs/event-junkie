-- V163 — Instagram drafts (ADR-048, #3366).
--
-- The importer reads the posts of a venue's Instagram business account, the model drafts events
-- from each caption, and a person approves every draft. Nothing here writes to `event`: an approved
-- draft becomes an event through the New event form, and `event_id` then points at it.
--
-- `instagram_venue` lists the venues whose account is read. A row is the switch: deleting it stops
-- the reading (ADR-048 rule 8). The handle comes from `venue.instagram_url`.
--
-- Unqualified table names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

CREATE TABLE instagram_venue
(
    venue_id   BIGINT      NOT NULL PRIMARY KEY REFERENCES venue (id) ON DELETE CASCADE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One row per event a post announces, or one row with no date for a post that announces none. The
-- second kind keeps a read post from going to the model again.
CREATE TABLE instagram_draft
(
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    venue_id         BIGINT           NOT NULL REFERENCES venue (id) ON DELETE CASCADE,
    media_id         TEXT             NOT NULL,
    permalink        TEXT             NOT NULL CHECK (permalink ~ '^https://www\.instagram\.com/'),
    posted_at        TIMESTAMPTZ      NOT NULL,
    -- Kept only while a person has still to judge the draft (ADR-048 rule 6).
    caption          TEXT,
    event_date       DATE,
    start_time       TIME,
    title            TEXT,
    acts             TEXT[]           NOT NULL DEFAULT '{}',
    price_presale    NUMERIC(10, 2),
    price_box_office NUMERIC(10, 2),
    -- The passage of the caption the date rests on.
    evidence         TEXT,
    confidence       DOUBLE PRECISION CHECK (confidence BETWEEN 0 AND 1),
    model            TEXT,
    status           TEXT             NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'NOT_AN_EVENT')),
    event_id         BIGINT REFERENCES event (id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ      NOT NULL DEFAULT now(),
    reviewed_at      TIMESTAMPTZ,
    CONSTRAINT instagram_draft_caption_only_pending CHECK (caption IS NULL OR status = 'PENDING'),
    CONSTRAINT instagram_draft_event_has_date CHECK (status = 'NOT_AN_EVENT' OR (event_date IS NOT NULL AND title IS NOT NULL)),
    CONSTRAINT instagram_draft_one_per_post_and_date UNIQUE NULLS NOT DISTINCT (media_id, event_date)
);

-- The review list per venue and date, where drafts of one night from several posts meet.
CREATE INDEX idx_instagram_draft_venue_date ON instagram_draft (venue_id, event_date, status);

-- The census accounts of #2592 that are business accounts and post events. RSO, Prisma and Mom's
-- Limousine Service had no Instagram link stored.
UPDATE venue SET instagram_url = 'https://www.instagram.com/rso.berlin/' WHERE slug = 'rso-berlin' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/prisma.berlin/' WHERE slug = 'prisma' AND instagram_url IS NULL;
UPDATE venue SET instagram_url = 'https://www.instagram.com/momslimousineservice/'
WHERE slug = 'mom-s-limousine-service' AND instagram_url IS NULL;

INSERT INTO instagram_venue (venue_id)
SELECT id
FROM venue
WHERE slug IN ('rso-berlin', 'prisma', 'mokka-mitte-bar', 'cafe-cralle', 'mom-s-limousine-service', 'brauhaus-neulich', 'splitter-bar')
ON CONFLICT DO NOTHING;
