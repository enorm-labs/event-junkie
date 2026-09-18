-- The MusicBrainz verdict on each artist row, and the id it resolved to (ADR-031, #1567).
--
-- Three columns and nothing else. `musicbrainz_match` is what the lookup decided: EXACT when one
-- MusicBrainz artist carries this name, AMBIGUOUS when several do or only an alias does, NONE when
-- none does, UNCHECKED until the sweep has looked. The id is stored only for EXACT, which the second
-- CHECK enforces so a verdict that flips to AMBIGUOUS cannot leave a stale id behind.
-- `musicbrainz_checked_at` is when that verdict was reached; a row whose `updated_at` is newer was
-- renamed since and is looked up again.
--
-- Every existing row starts UNCHECKED, and the sweep drains them in bounded slices after each
-- import rather than in one nightly burst. The partial index is what makes "the oldest UNCHECKED
-- rows" cheap to find while that backfill runs, and empty afterwards.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE artist
    ADD COLUMN musicbrainz_id         TEXT,
    ADD COLUMN musicbrainz_match      TEXT        NOT NULL DEFAULT 'UNCHECKED',
    ADD COLUMN musicbrainz_checked_at TIMESTAMPTZ;

ALTER TABLE artist
    ADD CONSTRAINT artist_musicbrainz_match_check
        CHECK (musicbrainz_match IN ('EXACT', 'AMBIGUOUS', 'NONE', 'UNCHECKED'));

ALTER TABLE artist
    ADD CONSTRAINT artist_musicbrainz_id_only_when_exact
        CHECK (musicbrainz_id IS NULL OR musicbrainz_match = 'EXACT');

CREATE INDEX artist_musicbrainz_unchecked_idx
    ON artist (id)
    WHERE musicbrainz_match = 'UNCHECKED';
