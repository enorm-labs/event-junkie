import type { VenueCharacter } from '@/api/types'

// The venue character tags, in display order. A Record so the compiler rejects a missing or unknown slug against
// the generated union, which comes from `VenueCharacterTag` in events-core. Labels live in i18n under
// `venueCharacter.<slug>`; an unknown slug renders as itself.

const DISPLAY_ORDER: Record<VenueCharacter, true> = {
  queer: true,
  'sex-positive': true,
  'diy-collective': true,
  'awareness-team': true,
  'safer-space-policy': true,
  'quiet-room': true,
  'all-gender-toilets': true,
  'free-water': true,
  'smoke-free': true,
  'dress-code': true,
  'fetish-dress-code': true,
  'no-photo-policy': true,
  'cash-only': true,
  'wheelchair-accessible': true,
}

export const VENUE_CHARACTERS: readonly string[] = Object.keys(DISPLAY_ORDER)

/** The BFF orders tags by slug; this puts them in display order, an unknown slug last. */
export function inCharacterOrder<T>(items: readonly T[], slug: (item: T) => string): T[] {
  const rank = (item: T) => {
    const index = VENUE_CHARACTERS.indexOf(slug(item))
    return index < 0 ? VENUE_CHARACTERS.length : index
  }
  return [...items].sort((a, b) => rank(a) - rank(b))
}
