-- The flyers the flyer-lineup check has read (#5048, ADR-059). One row per event and stored image, so a new
-- flyer for the same night is read again and an unchanged one is not.
CREATE TABLE event_flyer_check
(
    event_id     BIGINT      NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    -- `cached_image.content_hash` of the image that was read.
    content_hash TEXT        NOT NULL,
    model        TEXT        NOT NULL,
    checked_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, content_hash)
);
