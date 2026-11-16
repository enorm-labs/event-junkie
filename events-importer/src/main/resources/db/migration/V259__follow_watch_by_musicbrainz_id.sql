-- V259 — a watch (#5214): a follow of an act that has no artist row yet, held by its MusicBrainz id. The BFF turns it
-- into an artist follow once an artist row with that id has the verdict EXACT (ADR-031).
--
-- `name` is the name MusicBrainz gave when the visitor picked the act, so the Following page can list a watch with
-- nothing to join. MusicBrainz names run longer than a saved search's 80 characters.
--
-- The previous BFF reads on through the rollout: it lists follows by a join on `entity_id`, which a watch does not have.

-- Each CHECK is widened: the line after the drop adds it back with every old case kept, in the same transaction.
-- squawk-ignore ban-drop-constraint
ALTER TABLE accounts.follow DROP CONSTRAINT follow_kind_check;
ALTER TABLE accounts.follow ADD CONSTRAINT follow_kind_check
    CHECK (kind IN ('artist', 'venue', 'promoter', 'festival', 'search', 'watch'));

ALTER TABLE accounts.follow ADD COLUMN mbid TEXT
    CONSTRAINT follow_mbid_shape CHECK (mbid ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$');

-- squawk-ignore ban-drop-constraint
ALTER TABLE accounts.follow DROP CONSTRAINT follow_target;
ALTER TABLE accounts.follow ADD CONSTRAINT follow_target CHECK (
    (kind = 'search' AND query IS NOT NULL AND entity_id IS NULL AND mbid IS NULL)
        OR (kind = 'watch' AND mbid IS NOT NULL AND entity_id IS NULL AND query IS NULL)
        OR (kind NOT IN ('search', 'watch') AND entity_id IS NOT NULL AND query IS NULL AND mbid IS NULL));

ALTER TABLE accounts.follow ADD CONSTRAINT follow_watch_name
    CHECK (kind <> 'watch' OR (name IS NOT NULL AND length(name) BETWEEN 1 AND 200));

CREATE UNIQUE INDEX follow_watch_key ON accounts.follow (account_id, mbid) WHERE kind = 'watch';
CREATE INDEX follow_watch_mbid_idx ON accounts.follow (mbid) WHERE kind = 'watch';

-- The conversion joins every watch to the artist row that carries its id.
CREATE INDEX artist_musicbrainz_id_idx ON events.artist (musicbrainz_id) WHERE musicbrainz_id IS NOT NULL;
