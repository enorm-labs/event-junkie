import type { EventType } from '@/api/types'

// Every event type, in display order. A Record so the compiler rejects a missing or unknown key
// against the generated union, which `api-schema-parity.sh` keeps equal to `EventType` in events-core.
const DISPLAY_ORDER: Record<EventType, true> = {
  CONCERT: true,
  FESTIVAL: true,
  PARTY: true,
  QUIZ: true,
  SHOW: true,
  COMEDY: true,
  SCREENING: true,
  EXHIBITION: true,
  READING: true,
  OTHER: true,
}

export const EVENT_TYPES = Object.keys(DISPLAY_ORDER) as readonly EventType[]
