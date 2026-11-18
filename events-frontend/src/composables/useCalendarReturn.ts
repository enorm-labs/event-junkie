import type { EventInput } from '@fullcalendar/vue3'
import { onBeforeUnmount, onMounted } from 'vue'

/** The view and the date a calendar showed: `datesSet` reports it, and a return opens on it. */
export interface CalendarPlace {
  view: string
  date: string
}

interface Snapshot extends CalendarPlace {
  token: number
  scope: string
  events: EventInput[]
}

// Memory only, never stored on the device. The history entry carries just the token, so a fresh
// visit or a reload opens on the default view.
const snapshots = new Map<string, Snapshot>()
let lastToken = 0

/**
 * Back from an event reopens the calendar on the view and the date the visitor left, with the
 * events it showed, in the same tick. The router's restored scroll then has the height to land on
 * (#1111); the page refetches behind it. `scope` names what the events depend on besides the range.
 */
export function useCalendarReturn(key: string, scope: () => string) {
  const stateKey = `calendarSnapshot:${key}`
  const last = snapshots.get(key)
  const restored =
    last && window.history.state?.[stateKey] === last.token && last.scope === scope()
      ? last
      : undefined
  const token = restored?.token ?? ++lastToken
  let place: CalendarPlace | undefined = restored

  onMounted(() => window.history.replaceState({ ...window.history.state, [stateKey]: token }, ''))

  return {
    restored,
    /** Feed it every `datesSet`, so the snapshot holds the last place. */
    track(next: CalendarPlace) {
      place = next
    },
    /** On leaving, keep what `events` returns; `undefined` (an error, say) keeps nothing. */
    keepOnLeave(events: () => EventInput[] | undefined) {
      onBeforeUnmount(() => {
        const shown = events()
        if (place && shown) snapshots.set(key, { ...place, token, scope: scope(), events: shown })
        else snapshots.delete(key)
      })
    },
  }
}
