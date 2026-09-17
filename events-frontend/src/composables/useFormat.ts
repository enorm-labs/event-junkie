import { useI18n } from 'vue-i18n'

import {
  daysBetween,
  formatDate,
  formatShortDate,
  formatTime,
  formatWeekday,
  humaniseEventType,
} from '@/lib/format'
import { INTL_LOCALES, isLocale } from '@/i18n/locales'

/** The times an event can carry: three candidates for the start, and the end the venue stated. */
type EventTimes = {
  eventDate?: string | null
  startTime?: string | null
  doorsTime?: string | null
  assumedStartTime?: string | null
  endDate?: string | null
  endTime?: string | null
}

/**
 * Locale-aware wrappers around the pure helpers in `lib/format.ts`.
 *
 * The helpers stay pure and take the locale as an argument so they remain unit-testable without an
 * app; this composable is the thin layer that supplies it from the active i18n instance.
 */
export function useFormat() {
  const { locale, t, te } = useI18n()
  const intlLocale = () => (isLocale(locale.value) ? INTL_LOCALES[locale.value] : 'en-GB')

  /**
   * The end, in the words that fit how far away it is (ADR-029): `06:00` for the next morning,
   * `Mon 10:00` when it is further, and `23:30` on the same day. Null when the venue stated none.
   * Never derived: a guessed end beside a `~` start would be a second kind of guess on one line.
   */
  const endLabel = (event: EventTimes): { text: string; farAway: boolean } | null => {
    if (!event.endTime || !event.endDate || !event.eventDate) return null
    const farAway = daysBetween(event.eventDate, event.endDate) > 1
    const time = formatTime(event.endTime)
    return {
      text: farAway ? `${formatWeekday(event.endDate, intlLocale())} ${time}` : time,
      farAway,
    }
  }

  return {
    /**
     * `formatDate` bound to the active locale — "Fri, 12 Jun 2026" / "Fr., 12. Juni 2026".
     *
     * Maps the UI locale to a formatting tag first: bare `en` means US conventions to `Intl`, and
     * would render the month first (see {@link INTL_LOCALES}).
     */
    formatDate: (isoDate?: string | null) =>
      formatDate(isoDate, isLocale(locale.value) ? INTL_LOCALES[locale.value] : 'en-GB'),

    /** `formatWeekday` bound to the active locale — "Fri" / "Fr.", for "running since". */
    formatWeekday: (isoDate?: string | null) => formatWeekday(isoDate, intlLocale()),

    /** `formatShortDate` bound to the active locale — the compact view's date. */
    formatShortDate: (isoDate?: string | null) =>
      formatShortDate(isoDate, isLocale(locale.value) ? INTL_LOCALES[locale.value] : 'en-GB'),

    /**
     * The time a card, row or detail header shows: the start when the venue published one, else
     * the doors labelled as doors, else the BFF's guess marked as one (`~23:00`), else a note that
     * no time was announced (#1383, #1384).
     *
     * Doors is labelled because it is a different fact — `19:00` beside a 21:00 concert reads as
     * our mistake. The guess is marked because the list sorts by it and a reader should see the
     * same number, but never take it for the venue's word; `eventTimeHint` says so in words. The
     * note is the last resort, for a BFF that does not send the guess yet.
     */
    formatEventTime: (event: EventTimes) => {
      const end = endLabel(event)
      if (event.startTime) {
        const start = formatTime(event.startTime)
        if (!end) return start
        // `Fri 22:00 – Mon 10:00`: once the end names a day, so does the start.
        const day = end.farAway ? `${formatWeekday(event.eventDate, intlLocale())} ` : ''
        return `${day}${start} – ${end.text}`
      }
      if (event.doorsTime) return t('events.card.doors', { time: formatTime(event.doorsTime) })
      if (event.assumedStartTime)
        return t('events.card.assumed', { time: formatTime(event.assumedStartTime) })
      if (end) return t('events.card.until', { time: end.text })
      return t('events.card.timeUnknown')
    },

    /**
     * The date line: one day, or `Fri, 12 Jun 2026 – Mon, 15 Jun 2026` for a run the venue gave an
     * end date but no end time (an exhibition on for weeks). A span with a time keeps one date here
     * and says the rest in `formatEventTime`.
     */
    formatEventDates: (event: EventTimes) => {
      const start = formatDate(event.eventDate, intlLocale())
      return event.endDate && !event.endTime && event.endDate !== event.eventDate
        ? `${start} – ${formatDate(event.endDate, intlLocale())}`
        : start
    },

    /** The words behind a `~` time, for a `title` and a screen reader; null when the time is a fact. */
    eventTimeHint: (event: EventTimes) =>
      !event.startTime && !event.doorsTime && event.assumedStartTime
        ? t('events.card.assumedHint')
        : null,

    /**
     * The display label for an event type.
     *
     * Looks the enum value up in the `eventType.*` catalogue, and falls back to sentence-casing
     * the constant when the BFF sends a value the frontend has not been taught yet. That fallback
     * matters: `EventType` lives in `events-core` and can gain a value in a backend release that
     * ships before the frontend does — "Silent disco" reads acceptably in the meantime, whereas
     * `SILENT_DISCO` or an empty label does not.
     */
    formatEventType: (eventType?: string | null) => {
      if (!eventType) return ''
      const key = `eventType.${eventType}`
      return te(key) ? t(key) : humaniseEventType(eventType)
    },

    /**
     * The one word for a status that changes whether the reader goes, and `''` for `SCHEDULED`,
     * which is every other event and says nothing. Same guard as `formatEventType`: `EventStatus`
     * lives in `events-core`, and a value the catalogue lacks reads as its sentence-cased constant.
     */
    formatEventStatus: (status?: string | null) => {
      if (!status || status === 'SCHEDULED') return ''
      const key = `events.status.${status}`
      return te(key) ? t(key) : humaniseEventType(status)
    },
  }
}
