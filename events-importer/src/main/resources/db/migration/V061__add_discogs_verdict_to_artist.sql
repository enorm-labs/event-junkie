-- The Discogs verdict on an artist row MusicBrainz does not know, and the id it resolved to (#2026, ADR-035).
--
-- The shape of V037, for a second index. `discogs_match` is what the lookup decided: EXACT when one
-- Discogs artist carries this name, AMBIGUOUS when several do, NONE when none does, UNCHECKED until
-- the sweep has looked. Only a row whose `musicbrainz_match` is NONE is ever looked at, so every
-- other row stays UNCHECKED. The id is stored only for EXACT, which the second CHECK enforces.
-- `discogs_checked_at` is when that verdict was reached; a row whose `updated_at` is newer was renamed
-- since and is looked up again.
--
-- An id and a verdict, nothing else. Discogs' API terms forbid storing its content longer than a
-- service needs it and showing it more than six hours stale; an id and the page link show none.
--
-- The partial index serves the backfill: the NONE rows nobody has asked Discogs about.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE artist
    ADD COLUMN discogs_id         BIGINT,
    ADD COLUMN discogs_match      TEXT        NOT NULL DEFAULT 'UNCHECKED',
    ADD COLUMN discogs_checked_at TIMESTAMPTZ;

ALTER TABLE artist
    ADD CONSTRAINT artist_discogs_match_check
        CHECK (discogs_match IN ('EXACT', 'AMBIGUOUS', 'NONE', 'UNCHECKED'));

ALTER TABLE artist
    ADD CONSTRAINT artist_discogs_id_only_when_exact
        CHECK (discogs_id IS NULL OR discogs_match = 'EXACT');

CREATE INDEX artist_discogs_unchecked_idx
    ON artist (id)
    WHERE musicbrainz_match = 'NONE' AND discogs_match = 'UNCHECKED';
