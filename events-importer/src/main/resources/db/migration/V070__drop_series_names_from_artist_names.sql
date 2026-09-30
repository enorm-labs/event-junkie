-- Three artist rows carry the series a house billed beside the act (#2072): silent green's
-- `Reg Meuross – Sonic Morgue` and `Half Light – Abul Mogard`, and Theater im Delphi's
-- `Delphis Orakel – Stroum`. The scrapers now drop the series. The Reg Meuross nights are past, so
-- no import renames that row; this does, in V045's shape, and the other two go the same way.
--
-- Keyed on slug, and a no-op where the row is absent or its new slug is already taken: a taken slug
-- is not evidence of the same act. The lookups ask a renamed row again under its real name.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

WITH renamed(old_slug, new_slug, new_name) AS (VALUES
    ('reg-meuross-sonic-morgue', 'reg-meuross', 'Reg Meuross'),
    ('half-light-abul-mogard', 'abul-mogard', 'Abul Mogard'),
    ('delphis-orakel-stroum', 'stroum', 'Stroum')
)
UPDATE artist a
SET slug                   = r.new_slug,
    name                   = r.new_name,
    musicbrainz_id         = NULL,
    musicbrainz_match      = 'UNCHECKED',
    musicbrainz_checked_at = NULL,
    discogs_id             = NULL,
    discogs_match          = 'UNCHECKED',
    discogs_checked_at     = NULL
FROM renamed r
WHERE a.slug = r.old_slug
  AND NOT EXISTS (SELECT 1 FROM artist t WHERE t.slug = r.new_slug);
