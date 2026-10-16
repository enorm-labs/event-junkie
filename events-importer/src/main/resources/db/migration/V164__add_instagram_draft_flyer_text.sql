-- V164 — the text the model reads off an Instagram post's images, and the doors time (ADR-049, #3375).
--
-- The importer now sends a post's images to the model beside the caption. The model copies the text
-- it reads on them, and the checks accept a fact quoted from it. The text is kept for the person who
-- approves the draft, and only while the draft waits, like the caption (ADR-048 rule 6). The images
-- themselves are never stored. A flyer often gives only the doors time, which the event stores
-- apart from the start.

ALTER TABLE instagram_draft
    ADD COLUMN flyer_text TEXT CONSTRAINT instagram_draft_flyer_text_only_pending CHECK (flyer_text IS NULL OR status = 'PENDING'),
    ADD COLUMN doors_time TIME;
