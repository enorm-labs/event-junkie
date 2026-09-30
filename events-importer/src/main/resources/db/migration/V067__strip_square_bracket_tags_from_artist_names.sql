-- Three artist rows kept a square-bracket tag the venue wrote next to the act: a country code
-- (`Endica [Esp]`, `Marí Kozlovska [Esp]`, VOID Club) or a format (`Ohnmacht [Live]`, Aeden) (#2078).
-- The shared rules now strip these on import. All three bill past events only, so no import renames
-- them; this does, in V045's shape.
--
-- Keyed on slug, and a no-op where the row is absent or its new slug is already taken: a taken slug
-- is not evidence of the same act. The lookups ask a renamed row again under its real name.
--
-- Unqualified table name, deliberately: Flyway sets `search_path` from `spring.flyway.schemas`
-- before running this (ADR-004).

WITH renamed(old_slug, new_slug, new_name) AS (VALUES
    ('endica-esp', 'endica', 'Endica'),
    ('mari-kozlovska-esp', 'mari-kozlovska', 'Marí Kozlovska'),
    ('ohnmacht-live', 'ohnmacht', 'Ohnmacht')
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
