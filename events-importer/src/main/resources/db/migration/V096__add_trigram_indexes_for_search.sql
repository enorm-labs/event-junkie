-- Trigram indexes for the public name search (#2533). TextSearch.kt in events-bff tests each column
-- as `replace(<column>_search, ' ', '') LIKE '%term%'`, and only an index on that same expression
-- serves it. Without them the header search read every upcoming event per keystroke, and on staging
-- the database CPU, not the BFF, set its ceiling.
--
-- pg_trgm comes from V090. A term shorter than three characters has no trigram, so it still reads the
-- table; that is a few milliseconds at this size.
--
-- Unqualified names, deliberately: Flyway sets `search_path` from `spring.flyway.schemas` (ADR-004).

CREATE INDEX idx_event_title_search_trgm ON event USING gin (replace(title_search, ' ', '') gin_trgm_ops);
CREATE INDEX idx_event_subtitle_search_trgm ON event USING gin (replace(subtitle_search, ' ', '') gin_trgm_ops);
CREATE INDEX idx_venue_name_search_trgm ON venue USING gin (replace(name_search, ' ', '') gin_trgm_ops);
CREATE INDEX idx_artist_name_search_trgm ON artist USING gin (replace(name_search, ' ', '') gin_trgm_ops);
CREATE INDEX idx_promoter_name_search_trgm ON promoter USING gin (replace(name_search, ' ', '') gin_trgm_ops);
