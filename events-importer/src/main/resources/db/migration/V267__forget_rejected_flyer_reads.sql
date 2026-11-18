-- Every flyer the flyer-lineup check has read so far was rejected as NO_FLYER_TEXT, because the validator read only a
-- string `flyerText` (#5258). A row keeps its flyer from being read again, so deleting all of them lets the next pass
-- read those flyers. The table stores no outcome, and production logs no pass that read a flyer before this migration.
DELETE FROM event_flyer_check;
