// The venue character tags, in display order — the same order and slugs as `VenueCharacterTag` in the
// importer. Labels live in i18n under `venueCharacter.<slug>`; an unknown slug renders as itself.

export const VENUE_CHARACTERS: readonly string[] = [
  'queer',
  'sex-positive',
  'diy-collective',
  'awareness-team',
  'safer-space-policy',
  'quiet-room',
  'all-gender-toilets',
  'free-water',
  'smoke-free',
  'dress-code',
  'fetish-dress-code',
  'no-photo-policy',
  'cash-only',
  'wheelchair-accessible',
]

/** The BFF orders tags by slug; this puts them in display order, an unknown slug last. */
export function inCharacterOrder<T>(items: readonly T[], slug: (item: T) => string): T[] {
  const rank = (item: T) => {
    const index = VENUE_CHARACTERS.indexOf(slug(item))
    return index < 0 ? VENUE_CHARACTERS.length : index
  }
  return [...items].sort((a, b) => rank(a) - rank(b))
}
