import type { Component } from 'vue'
import {
  AudioLines,
  AudioWaveform,
  Beer,
  BookOpen,
  CircleHelp,
  Clapperboard,
  Disc3,
  Drama,
  Drum,
  Frame,
  Globe,
  Guitar,
  Landmark,
  Laugh,
  Mic,
  MicVocal,
  Music,
  Music4,
  PartyPopper,
  Piano,
  Radio,
  Skull,
  Speaker,
  Ticket,
  Trees,
  Zap,
} from '@lucide/vue'
import type { EventSummary } from '@/api/types'

/** The ground a title poster stands on: a `bg-poster-*` token, or `bg-muted` for `other`. */
export type PosterGround =
  | 'electronic'
  | 'voice'
  | 'rock'
  | 'roots'
  | 'concert'
  | 'festival'
  | 'stage'
  | 'words'
  | 'other'

export interface PosterArt {
  icon: Component
  ground: PosterGround
}

type EventType = NonNullable<EventSummary['eventType']>

/** One drawing per filter family (`GENRE_FAMILIES`); the thirteen share five grounds. */
const BY_FAMILY: Record<string, PosterArt> = {
  electronic: { icon: Disc3, ground: 'electronic' },
  'hip-hop': { icon: MicVocal, ground: 'voice' },
  pop: { icon: Mic, ground: 'voice' },
  charts: { icon: Radio, ground: 'voice' },
  rock: { icon: Guitar, ground: 'rock' },
  punk: { icon: Zap, ground: 'rock' },
  metal: { icon: Skull, ground: 'rock' },
  wave: { icon: AudioWaveform, ground: 'rock' },
  'soul-funk': { icon: Drum, ground: 'roots' },
  'jazz-blues': { icon: Piano, ground: 'roots' },
  folk: { icon: Guitar, ground: 'roots' },
  'latin-world': { icon: Globe, ground: 'roots' },
  classical: { icon: Music4, ground: 'roots' },
}

const BY_TYPE: Record<EventType, PosterArt> = {
  CONCERT: { icon: Music, ground: 'concert' },
  PARTY: { icon: Speaker, ground: 'electronic' },
  FESTIVAL: { icon: PartyPopper, ground: 'festival' },
  COMEDY: { icon: Laugh, ground: 'stage' },
  SHOW: { icon: Drama, ground: 'stage' },
  READING: { icon: BookOpen, ground: 'words' },
  SCREENING: { icon: Clapperboard, ground: 'words' },
  QUIZ: { icon: CircleHelp, ground: 'words' },
  EXHIBITION: { icon: AudioLines, ground: 'words' },
  OTHER: { icon: AudioLines, ground: 'other' },
}

/** A venue with no programme families yet is drawn by what kind of place it is. */
const BY_VENUE_TYPE: Record<string, PosterArt> = {
  club: { icon: Speaker, ground: 'electronic' },
  'live-venue': { icon: Music, ground: 'concert' },
  arena: { icon: Ticket, ground: 'concert' },
  'open-air': { icon: Trees, ground: 'festival' },
  theatre: { icon: Drama, ground: 'stage' },
  'cultural-centre': { icon: Landmark, ground: 'words' },
  gallery: { icon: Frame, ground: 'words' },
  cinema: { icon: Clapperboard, ground: 'words' },
  bar: { icon: Beer, ground: 'other' },
}

/** The types a genre describes better than the type does: "concert" says nothing about the night. */
const MUSIC_TYPES: readonly (EventType | undefined)[] = ['CONCERT', 'PARTY', 'OTHER', undefined]

/**
 * The drawing and ground behind a title poster. A music event takes its first known genre family;
 * a reading or a quiz keeps its type, whatever genre a venue tagged it with. A venue passes no
 * event type: its programme families first, then its venue type.
 */
export function posterArt(
  eventType?: EventType | null,
  families?: readonly string[] | null,
  venueTypes?: readonly string[] | null,
): PosterArt {
  const type = eventType ?? undefined
  if (MUSIC_TYPES.includes(type)) {
    const family = families?.find((slug) => slug in BY_FAMILY)
    if (family) return BY_FAMILY[family]!
  }
  const venueType = type ? undefined : venueTypes?.find((slug) => slug in BY_VENUE_TYPE)
  return venueType ? BY_VENUE_TYPE[venueType]! : BY_TYPE[type ?? 'OTHER']
}
