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
 * Locale-aware wrappers around the pure helpers in `lib/format.ts`, which take the locale as an
 * argument so the unit tests need no app.
 */
export function useFormat() {
  const { locale, t, te } = useI18n()
  const intlLocale = () => (isLocale(locale.value) ? INTL_LOCALES[locale.value] : 'en-GB')

  /**
   * The end, in the words that fit how far away it is (ADR-029): `06:00` for the next morning,
   * `Mon 10:00` further out, `23:30` on the same day. Null when the venue stated none; never derived.
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
     * `formatDate` bound to the active locale, mapped to a formatting tag first: bare `en` means US
     * conventions to `Intl` ({@link INTL_LOCALES}).
     */
    formatDate: (isoDate?: string | null) =>
      formatDate(isoDate, isLocale(locale.value) ? INTL_LOCALES[locale.value] : 'en-GB'),

    /** `formatWeekday` bound to the active locale — "Fri" / "Fr.", for "running since". */
    formatWeekday: (isoDate?: string | null) => formatWeekday(isoDate, intlLocale()),

    /** `formatShortDate` bound to the active locale — the compact view's date. */
    formatShortDate: (isoDate?: string | null) =>
      formatShortDate(isoDate, isLocale(locale.value) ? INTL_LOCALES[locale.value] : 'en-GB'),

    /**
     * The time a card, row or detail header shows: the start when the venue published one, else the
     * doors labelled as doors, else the BFF's guess marked as one (`~23:00`), else a note that no
     * time was announced (#1383, #1384). Doors is labelled because `19:00` beside a 21:00 concert
     * reads as our mistake; the guess is marked because the list sorts by it, and `eventTimeHint`
     * says so in words.
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
     * The date line: one day, or `Fri, 12 Jun 2026 – Mon, 15 Jun 2026` for a run with an end date
     * and no end time. A span with a time keeps one date here and says the rest in `formatEventTime`.
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
     * The display label for an event type, from the `eventType.*` catalogue, sentence-casing the
     * constant when the BFF sends a value the frontend has not been taught: `EventType` lives in
     * `events-core` and can gain a value in a backend release that ships first.
     */
    formatEventType: (eventType?: string | null) => {
      if (!eventType) return ''
      const key = `eventType.${eventType}`
      return te(key) ? t(key) : humaniseEventType(eventType)
    },

    /**
     * The one word for a status that changes whether the reader goes, `''` for `SCHEDULED`. A
     * relocated event that knows where it went says so ("Moved to Hole44"). Same fallback as
     * `formatEventType`.
     */
    formatEventStatus: (status?: string | null, relocatedTo?: string | null) => {
      if (!status || status === 'SCHEDULED') return ''
      if (status === 'RELOCATED' && relocatedTo)
        return t('events.status.movedTo', { venue: relocatedTo })
      const key = `events.status.${status}`
      return te(key) ? t(key) : humaniseEventType(status)
    },
  }
}
