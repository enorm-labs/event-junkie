-- When an artist row's name last changed, so a lookup can tell a rename from any other write (#2053).
--
-- The MusicBrainz and Discogs lookups ask a touched row again when it was renamed after its verdict.
-- They read `updated_at` for that, but `trg_artist_updated_at` (V001) moves it on every UPDATE, a
-- verdict included. So each lookup's write re-queued the row for the other one, on every import that
-- touched it. This column moves only when the name does.
--
-- An existing row counts as named when it was created. A row a data migration renamed after its
-- verdict is not asked again; those renames fixed case and punctuation, which the match folds.
--
-- The trigger's `WHEN` is load-bearing: an R2DBC `save` writes every column, the unchanged name too.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

ALTER TABLE artist
    ADD COLUMN name_changed_at TIMESTAMPTZ NOT NULL DEFAULT now();

UPDATE artist
SET name_changed_at = created_at;

CREATE FUNCTION set_name_changed_at() RETURNS TRIGGER AS
$$
BEGIN
    NEW.name_changed_at = now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_artist_name_changed_at
    BEFORE UPDATE OF name
    ON artist
    FOR EACH ROW
    WHEN (OLD.name IS DISTINCT FROM NEW.name)
EXECUTE FUNCTION set_name_changed_at();
