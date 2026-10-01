import { computed } from 'vue'

import type { EventSummary } from '@/api/types'
import { useFormat } from '@/composables/useFormat'
import { isPastEvent, isRunningEvent, todayIso } from '@/lib/format'
import { useI18n } from 'vue-i18n'

/**
 * The derived display state an event tile carries, shared by `EventCard` and `EventRow` so the
 * two views of one event never disagree about it.
 *
 * @param event getter for the event, so the state follows a changing prop.
 */
export function useEventState(event: () => EventSummary) {
  const { eventTimeHint, formatEventStatus, formatEventType, formatWeekday } = useFormat()
  const { t } = useI18n()

  const isPast = computed(() => isPastEvent(event()))
  // A weekender in its second night: started, not over (ADR-029).
  const isRunning = computed(() => isRunningEvent(event()))
  const status = computed(() => formatEventStatus(event().status, event().relocatedTo))

  // Set only when the time shown is the BFF's guess (#1384); the tile carries it as a title.
  const timeHint = computed(() => eventTimeHint(event()))

  // An event on today gets a pulsing "live" dot. A running weekender is on today too; a cancelled
  // or moved one is not live.
  const isLive = computed(
    () =>
      !status.value &&
      ((Boolean(event().eventDate) && event().eventDate === todayIso()) || isRunning.value),
  )

  // `OTHER` is the importers' catch-all and tells a reader nothing, so it earns no pill.
  const eventType = computed(() =>
    event().eventType && event().eventType !== 'OTHER' ? formatEventType(event().eventType) : null,
  )

  /**
   * The one word that changes what the reader does next, and the only coloured thing in the meta
   * line. Past wins the slot ("Sold out" on last month's gig is stale); a cancelled, postponed or
   * relocated night next (#1550); then running, because "since Friday" is what a Sunday reader
   * needs first. Colour is emphasis on top of the word, never instead (WCAG 1.4.1).
   */
  const state = computed(() => {
    if (isPast.value) return { label: t('events.card.past'), class: 'text-muted-foreground' }
    if (status.value) return { label: status.value, class: 'text-destructive' }
    if (isRunning.value)
      return {
        label: t('events.card.runningSince', { day: formatWeekday(event().eventDate) }),
        class: 'text-primary',
      }
    if (event().soldOut) return { label: t('events.card.soldOut'), class: 'text-destructive' }
    if (event().free) return { label: t('events.card.free'), class: 'text-success' }
    return null
  })

  return { eventType, isLive, state, timeHint }
}
