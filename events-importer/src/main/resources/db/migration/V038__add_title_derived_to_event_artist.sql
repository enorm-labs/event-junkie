-- V038 — provenance on event_artist: was the name read off the event title? (#1145)
--
-- A headliner minted from a bare title is the one shape every series-as-artist defect has shared,
-- and until now the importer forgot that it guessed the moment it returned the list. The column is
-- provenance, not a verdict: a real act on its first Berlin date is title-derived too. The queue
-- the data-quality report builds from it adds two more conditions (one event, name equals title, or
-- no MusicBrainz match) before a row is worth a look.
--
-- DEFAULT false, so rows written before this release read as line-up-derived until their source is
-- re-imported with ?force=true; the update diff in AssociationSyncService writes the flag then.

ALTER TABLE event_artist
    ADD COLUMN title_derived BOOLEAN NOT NULL DEFAULT false;
