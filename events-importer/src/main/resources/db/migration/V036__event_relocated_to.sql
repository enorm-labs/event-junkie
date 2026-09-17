-- Where a RELOCATED event moved to, as the venue's own note names the house. Text, not a venue id:
-- the note is the only reliable thing, and the destination may be a house this site does not list.
-- Null on every other status, and on a note that names no destination (#1551, ADR-030).
ALTER TABLE event
    ADD COLUMN relocated_to TEXT;
