import { computed } from 'vue'

import type { EventSummary } from '@/api/types'
import { useFormat } from '@/composables/useFormat'
import { isOnNow, isPastEvent, isRunningEvent } from '@/lib/format'
import { isLiveNow } from '@/lib/longRuns'
import { useI18n } from 'vue-i18n'

/**
 * The derived display state an event tile carries, shared by `EventCard` and `EventRow` so the
 * two views of one event never disagree about it.
 *
 * @param event getter for the event, so the state follows a changing prop.
 */
export function useEventState(event: () => EventSummary) {
  const {
    eventTimeHint,
    formatEventStatus,
    formatEventType,
    formatRunState,
    formatSpokenLanguage,
  } = useFormat()
  const { t } = useI18n()

  const isPast = computed(() => isPastEvent(event()))
  // A weekender in its second night: started, not over (ADR-029).
  const isRunning = computed(() => isRunningEvent(event()))
  const status = computed(() => formatEventStatus(event().status, event().relocatedTo))

  // Set only when the time shown is the BFF's guess (#1384); the tile carries it as a title.
  const timeHint = computed(() => eventTimeHint(event()))

  // An event that has started gets a pulsing "live" dot, a running weekender too; a long run, a
  // cancelled or a moved one does not.
  const isLive = computed(() => !status.value && isLiveNow(event()))

  // `OTHER` is the importers' catch-all and tells a reader nothing, so it earns no pill.
  const eventType = computed(() =>
    event().eventType && event().eventType !== 'OTHER' ? formatEventType(event().eventType) : null,
  )

  // A fact beside the type, not a filter value: "Comedy · English" (#2523).
  const language = computed(() =>
    formatSpokenLanguage(event().spokenLanguages, event().subtitleLanguage),
  )

  /**
   * The one word that changes what the reader does next, and the only coloured thing in the meta
   * line. Past wins the slot ("Sold out" on last month's gig is stale); a cancelled, postponed or
   * relocated night next (#1550); then running, because "since Friday" is what a Sunday reader
   * needs first; then "on now" for tonight's once it has started (#358). Colour is emphasis on top
   * of the word, never instead (WCAG 1.4.1).
   */
  const state = computed(() => {
    if (isPast.value) return { label: t('events.card.past'), class: 'text-muted-foreground' }
    if (status.value) return { label: status.value, class: 'text-destructive' }
    if (isRunning.value) return { label: formatRunState(event()), class: 'text-primary' }
    if (isOnNow(event())) return { label: t('events.card.onNow'), class: 'text-primary' }
    if (event().soldOut) return { label: t('events.card.soldOut'), class: 'text-destructive' }
    if (event().free) return { label: t('events.card.free'), class: 'text-success' }
    return null
  })

  return { eventType, isLive, language, state, timeHint }
}
