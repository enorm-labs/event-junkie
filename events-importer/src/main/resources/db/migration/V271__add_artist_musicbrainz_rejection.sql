-- V271 — the MusicBrainz artists a person rejected as an artist row's match. The lookup sweep never picks a rejected id
-- for that row again, and `DELETE /api/admin/artists/{id}/musicbrainz-id` writes the rows.
--
-- `wilco` is a DJ at Frannz Club's Dämonenball nights, but the sweep matched it to the Chicago band Wilco, and the
-- enrichment copied the band's text, photo, links and facts onto it. `gomez` (V167) is the Duncker DJ matched to the
-- English band Gomez: V167 cleared that match but could not stop a rename from matching it again.

CREATE TABLE artist_musicbrainz_rejection (
    artist_id BIGINT NOT NULL REFERENCES artist (id) ON DELETE CASCADE,
    musicbrainz_id TEXT NOT NULL
        CONSTRAINT artist_musicbrainz_rejection_mbid_shape
            CHECK (musicbrainz_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    rejected_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (artist_id, musicbrainz_id)
);

INSERT INTO artist_musicbrainz_rejection (artist_id, musicbrainz_id)
SELECT id, '304cdd9d-a04e-4eb2-865c-980a553a0efd'
FROM artist
WHERE slug = 'gomez';

INSERT INTO artist_musicbrainz_rejection (artist_id, musicbrainz_id)
SELECT id, musicbrainz_id
FROM artist
WHERE slug = 'wilco'
  AND musicbrainz_id = '9e53f84d-ef44-4c16-9677-5fd4d78cbd7d';

-- The same write as the admin route. AMBIGUOUS, not NONE: NONE sends the row to the Discogs sweep, which can match the
-- band on Discogs. A licensed text and a Commons photo came from the match. A venue's own text has no credit and stays.
-- Guarded on the band's MBID, so a row already corrected is left alone.
UPDATE artist
SET musicbrainz_id = NULL,
    musicbrainz_match = 'AMBIGUOUS',
    musicbrainz_checked_at = now(),
    musicbrainz_enriched_at = NULL,
    description = CASE WHEN description_attribution IS NULL THEN description END,
    description_language = CASE WHEN description_attribution IS NULL THEN description_language END,
    description_attribution = NULL,
    description_licence_id = NULL,
    description_source_url = NULL,
    description_alt = NULL,
    description_alt_language = NULL,
    description_alt_attribution = NULL,
    description_alt_licence_id = NULL,
    description_alt_source_url = NULL,
    image_url = CASE WHEN image_source_url LIKE 'https://commons.wikimedia.org/%' THEN NULL ELSE image_url END,
    image_attribution = CASE WHEN image_source_url LIKE 'https://commons.wikimedia.org/%' THEN NULL ELSE image_attribution END,
    image_licence_id = CASE WHEN image_source_url LIKE 'https://commons.wikimedia.org/%' THEN NULL ELSE image_licence_id END,
    image_source_url = CASE WHEN image_source_url LIKE 'https://commons.wikimedia.org/%' THEN NULL ELSE image_source_url END,
    website_url = NULL,
    facebook_url = NULL,
    instagram_url = NULL,
    youtube_url = NULL,
    bandcamp_url = NULL,
    soundcloud_url = NULL,
    discogs_url = NULL,
    wikidata_url = NULL,
    resident_advisor_url = NULL,
    spotify_url = NULL,
    artist_type = NULL,
    founded = NULL,
    founded_in = NULL,
    country = NULL,
    comedian = NULL,
    wikidata_genres = NULL,
    wikidata_read_at = NULL,
    discogs_id = NULL,
    discogs_match = 'UNCHECKED',
    discogs_checked_at = NULL
WHERE slug = 'wilco'
  AND musicbrainz_id = '9e53f84d-ef44-4c16-9677-5fd4d78cbd7d';
