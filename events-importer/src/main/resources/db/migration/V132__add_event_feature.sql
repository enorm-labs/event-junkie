-- What kind of night an event is, as its own text states it (#2631): FLINTA*-only, queer, a dress code,
-- open end and the rest of the closed vocabulary `PartyFeature` in the importer.
--
-- Per event, not per venue: the same club runs a queer party on Friday and a techno night on Saturday.
-- No venue character tag (V099) fills a night that says nothing; each night stands alone.
--
-- A side table rather than a TEXT[] on `event`, because every feature keeps the phrase that set it,
-- so anyone can read why a night carries it. The importer derives the rows from the stored title and
-- description on every import and replaces them, so no row outlives the text it came from.
--
-- `feature` takes no CHECK, as V083 and V099 argue: the vocabulary is code.
--
-- A cue the rules cannot settle (a bare "FLINTA*", "queer artists") sets no feature. It goes to the
-- data-quality worklist instead, so `event_quality_flag.kind` gains UNCERTAIN_PARTY_FEATURE.
--
-- Unqualified table names, deliberately (ADR-004).

CREATE TABLE event_feature (
    event_id       BIGINT NOT NULL REFERENCES event (id) ON DELETE CASCADE,
    feature        TEXT   NOT NULL,
    matched_phrase TEXT   NOT NULL CHECK (matched_phrase <> ''),
    PRIMARY KEY (event_id, feature)
);

-- The public event list filters by feature across all events; the primary key serves the per-event reads.
CREATE INDEX idx_event_feature_feature ON event_feature (feature);

-- Widens the CHECK by one value: the next line adds it back with every old value kept, in the same transaction.
-- squawk-ignore ban-drop-constraint
ALTER TABLE event_quality_flag DROP CONSTRAINT event_quality_flag_kind_check;
ALTER TABLE event_quality_flag ADD CONSTRAINT event_quality_flag_kind_check
    CHECK (kind IN ('SLUGLESS_ARTIST', 'NON_ARTIST_NAME', 'HELD_BACK_PROMOTER_NAME', 'GENRE_EQUALS_TITLE', 'NON_GENRE_TOKEN',
                    'UNCERTAIN_PARTY_FEATURE'));
