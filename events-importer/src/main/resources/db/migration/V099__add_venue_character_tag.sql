-- What kind of space a venue says it is, beyond its type and its programme (#2379).
--
-- A side table rather than a TEXT[] beside `venue_types`, because every tag carries the URL of the
-- venue's own page that states it. A tag rests on that page and on nothing else: no review, listing
-- or impression. The URL is what lets anyone check the tag and remove it when the page changes.
--
-- `tag` is a slug from the closed vocabulary `VenueCharacterTag` in the importer. No CHECK on it,
-- as V083 argues for `venue_types`: the vocabulary is code.
--
-- No rows here. The proposed tags are in docs/venue-character-tags/PROPOSED.tsv, and
-- scripts/venue-character-tags.py writes them through the admin API once each one is verified.
--
-- Unqualified table name, deliberately (ADR-004).

CREATE TABLE venue_character_tag (
    venue_id   BIGINT      NOT NULL REFERENCES venue (id) ON DELETE CASCADE,
    tag        TEXT        NOT NULL,
    source_url TEXT        NOT NULL CHECK (source_url ~ '^https?://'),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (venue_id, tag)
);

-- The public venue list filters by tag across all venues; the primary key serves the per-venue reads.
CREATE INDEX idx_venue_character_tag_tag ON venue_character_tag (tag);
