-- What an EXACT MusicBrainz match fills in (ADR-031, step C, #1568).
--
-- Six more links, read from the artist's URL relationships; the artist type; and for a group,
-- orchestra or choir, when and where it formed. `founded` is MusicBrainz's partial date as text
-- ('1986', '1986-09' or '1986-09-06'), because a DATE column would have to invent a day.
--
-- The CHECK is the privacy notice made structural. For a Person the same MusicBrainz fields are a
-- birth date and a birthplace, which §4 of the notice promises not to hold, so the two columns can
-- be set only on a Group, Orchestra or Choir. `country` alone is allowed for anyone: it is where the
-- act is from, not where a person was born.
--
-- `musicbrainz_enriched_at` is when the sweep last read the entity; a row whose `musicbrainz_checked_at`
-- is newer was matched again since and is read again. The partial index finds the backfill — EXACT
-- rows the sweep has not read — and is empty once it has drained.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE artist
    ADD COLUMN bandcamp_url           TEXT,
    ADD COLUMN soundcloud_url         TEXT,
    ADD COLUMN discogs_url            TEXT,
    ADD COLUMN wikidata_url           TEXT,
    ADD COLUMN resident_advisor_url   TEXT,
    ADD COLUMN spotify_url            TEXT,
    ADD COLUMN artist_type            TEXT,
    ADD COLUMN founded                TEXT,
    ADD COLUMN founded_in             TEXT,
    ADD COLUMN country                TEXT,
    ADD COLUMN musicbrainz_enriched_at TIMESTAMPTZ;

ALTER TABLE artist
    ADD CONSTRAINT artist_type_check
        CHECK (artist_type IS NULL OR artist_type IN ('PERSON', 'GROUP', 'ORCHESTRA', 'CHOIR', 'OTHER'));

ALTER TABLE artist
    ADD CONSTRAINT artist_founded_only_for_ensembles
        CHECK ((founded IS NULL AND founded_in IS NULL) OR artist_type IN ('GROUP', 'ORCHESTRA', 'CHOIR'));

CREATE INDEX artist_musicbrainz_unenriched_idx
    ON artist (id)
    WHERE musicbrainz_match = 'EXACT' AND musicbrainz_enriched_at IS NULL;
