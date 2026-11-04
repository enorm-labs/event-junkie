import type { EventType } from './events'

/** A genre name the importer files under each slug of `GenreFamily.kt`. */
const GENRE_OF_FAMILY: Readonly<Record<string, string>> = {
  electronic: 'Electronic',
  'hip-hop': 'Hip Hop',
  pop: 'Pop',
  rock: 'Rock',
  punk: 'Punk',
  metal: 'Metal',
  wave: 'Wave',
  'soul-funk': 'Soul',
  'jazz-blues': 'Jazz',
  folk: 'Folk',
  'latin-world': 'Latin',
  classical: 'Classical',
  charts: 'Charts',
}

const MUSIC_TYPES: readonly EventType[] = ['CONCERT', 'PARTY', 'FESTIVAL']

/**
 * The genre to suggest for a new event, or blank. Several families say nothing about one night, so
 * only a venue with exactly one gets a suggestion (#4474).
 */
export function suggestedGenre(families: readonly string[], eventType: EventType): string {
  const [family] = families
  if (families.length !== 1 || family === undefined || !MUSIC_TYPES.includes(eventType)) return ''
  return GENRE_OF_FAMILY[family] ?? ''
}
