-- V201 — saved searches (#3890): a follow of `kind = 'search'` holds the events list's query string (V195) and
-- now a name. The page suggests one from the filters; the visitor can change it. Entity follows take their
-- name from `events` at every read, so they keep it NULL.
--
-- One search per query and account, so saving the same filters twice finds the first.

ALTER TABLE accounts.follow ADD COLUMN name TEXT;

ALTER TABLE accounts.follow
    ADD CONSTRAINT follow_search_name CHECK (kind <> 'search' OR (name IS NOT NULL AND length(name) BETWEEN 1 AND 80));

CREATE UNIQUE INDEX follow_search_key ON accounts.follow (account_id, query) WHERE kind = 'search';
