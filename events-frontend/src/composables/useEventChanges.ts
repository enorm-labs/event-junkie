import { useI18n } from 'vue-i18n'

import type { EventChange } from '@/api/types'
import { formatTime } from '@/lib/format'
import { useFormat } from '@/composables/useFormat'

/** One change as the event page shows it: "Start moved from 22:00 to 23:00", "2 days ago". */
export interface ChangeLine {
  text: string
  ago: string
}

type ChangeField = NonNullable<EventChange['field']>

/**
 * The lines for an event's changes (#2725), newest first as the BFF sent them. The When block
 * shows the time, date and status changes; the venue block shows the venue change under the
 * venue's name. A field or status without words is left out.
 */
export function useEventChanges() {
  const { t, te } = useI18n()
  const { formatShortDate, formatDaysAgo } = useFormat()

  /** A value in the words of its field: a short date, an `HH:mm` time, a venue's name. */
  function shown(field: ChangeField, value: string): string {
    if (field === 'EVENT_DATE' || field === 'END_DATE') return formatShortDate(value)
    if (field === 'START_TIME' || field === 'END_TIME') return formatTime(value)
    return value
  }

  function keyOf(field: ChangeField, to: string): string {
    if (field === 'VENUE') return 'events.detail.venueChange'
    if (field === 'STATUS') return `events.detail.when.change.STATUS.${to}`
    return `events.detail.when.change.${field}`
  }

  /** The lines of the changes whose field `keep` accepts. */
  function changeLines(
    changes: EventChange[] | null | undefined,
    keep: (field: ChangeField) => boolean,
  ): ChangeLine[] {
    return (changes ?? []).flatMap((change) => {
      if (!change.field || !change.from || !change.to || !change.seenAt) return []
      if (!keep(change.field)) return []
      const key = keyOf(change.field, change.to)
      if (!te(key)) return []
      const text = t(key, {
        from: shown(change.field, change.from),
        to: shown(change.field, change.to),
      })
      return [{ text, ago: formatDaysAgo(change.seenAt) }]
    })
  }

  return { changeLines }
}
