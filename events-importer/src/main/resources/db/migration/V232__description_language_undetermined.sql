-- A description written mostly in a non-Latin script is marked `und`, BCP 47 "undetermined" (#4665).
-- It is neither German nor English for certain, and its machine translation goes into English.
-- The translation's own language stays German or English.

-- Widens the CHECK by one value: the next line adds it back with every old value kept, in the same transaction.
-- squawk-ignore ban-drop-constraint
ALTER TABLE event DROP CONSTRAINT event_description_language_valid;
ALTER TABLE event ADD CONSTRAINT event_description_language_valid
    CHECK (description_language IS NULL OR description_language IN ('de', 'en', 'und'));
