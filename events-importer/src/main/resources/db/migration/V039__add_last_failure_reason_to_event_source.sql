-- V039 — the class of a source's last failure, next to its text (#708)
--
-- `last_error` holds the message of the last failed run, and a message cannot be counted: eighty-six
-- rows saying "www.<venue>: Name or service not known" in eighty-six spellings are eighty-six unrelated
-- venue problems until somebody reads them all. The importer already classifies every failure into a
-- constant for `importer_scrape_failures_total{reason}`, but that counter lives in the process and is
-- gone after a deploy — which is how #659 ended with the one question it had ("did the others fail
-- too?") unanswerable two days later.
--
-- NULL when the last run succeeded, or when the row was reset from a stuck RUNNING, where there is no
-- exception to classify. The gauge `importer_sources_failed{reason}` is the count of this column over
-- the sources currently FAILED.

ALTER TABLE event_source
    ADD COLUMN last_failure_reason TEXT;
