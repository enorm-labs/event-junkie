// The venue types, in display order — the same order and slugs as `VenueType` in the importer.
// Labels live in i18n under `venueType.<slug>`; an unknown slug renders as itself, the drift signal.

export const VENUE_TYPES: readonly string[] = [
  'club',
  'live-venue',
  'arena',
  'bar',
  'cultural-centre',
  'theatre',
  'cinema',
  'open-air',
  'gallery',
]
