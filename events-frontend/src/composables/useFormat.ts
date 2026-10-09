import { useI18n } from 'vue-i18n'

import {
  daysBetween,
  formatDate,
  formatMonthYear,
  formatShortDate,
  formatTime,
  formatWeekday,
  humaniseEventType,
  todayIso,
} from '@/lib/format'
import { isClosing, isLongRun } from '@/lib/longRuns'
import { INTL_LOCALES, isLocale } from '@/i18n/locales'
import { inCharacterOrder } from '@/lib/venueCharacters'

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

    /** `formatMonthYear` bound to the active locale — "October 2026" / "Oktober 2026". */
    formatMonthYear: (isoDate?: string | null) => formatMonthYear(isoDate, intlLocale()),

    /** `formatWeekday` bound to the active locale — "Fri" / "Fr.". */
    formatWeekday: (isoDate?: string | null) => formatWeekday(isoDate, intlLocale()),

    /**
     * What a running event says about its run: "Running since Fri" for a weekender, "Until Sun 1 Nov"
     * for a run of weeks, because "since" a month ago tells nobody anything, and "Closes Sun" in its
     * last days (#2594).
     */
    formatRunState: (event: EventTimes) => {
      if (isClosing(event, todayIso()))
        return t('events.card.closes', { day: formatWeekday(event.endDate, intlLocale()) })
      if (isLongRun(event))
        return t('events.card.runsUntil', { date: formatShortDate(event.endDate, intlLocale()) })
      return t('events.card.runningSince', { day: formatWeekday(event.eventDate, intlLocale()) })
    },

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

    /** The end as `formatEventTime` words it, alone; null when the venue stated none. */
    formatEventEnd: (event: EventTimes) => endLabel(event)?.text ?? null,

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

    /** The label for a venue type slug; the slug itself for one the frontend has not been taught. */
    formatVenueType: (slug: string) => (te(`venueType.${slug}`) ? t(`venueType.${slug}`) : slug),

    /** The label for a venue character tag slug, with the same fallback as `formatVenueType`. */
    formatVenueCharacter: (slug: string) =>
      te(`venueCharacter.${slug}`) ? t(`venueCharacter.${slug}`) : slug,

    /** The label for a party-feature slug (#2631), with the same fallback as `formatVenueCharacter`. */
    formatPartyFeature: (slug: string) =>
      te(`partyFeature.${slug}`) ? t(`partyFeature.${slug}`) : slug,

    /** The label for a time-of-night slot, with the same fallback as `formatVenueType`. */
    formatTimeOfDay: (slug: string) =>
      te(`events.filters.timesOfDay.${slug}`) ? t(`events.filters.timesOfDay.${slug}`) : slug,

    /** The label for a genre family slug, with the same fallback as `formatVenueType`. */
    formatFamily: (slug: string) =>
      te(`events.filters.families.${slug}`) ? t(`events.filters.families.${slug}`) : slug,

    /**
     * A venue's types, character tags and capacity as one meta line: "Club · Queer · ~1,700 people". The
     * capacity is rounded to two significant figures, because venues state it loosely and seated and standing differ.
     */
    formatVenueFacts: (venue: {
      venueTypes?: string[]
      characterTags?: string[]
      capacity?: number | null
    }) =>
      [
        ...(venue.venueTypes ?? []).map((slug) =>
          te(`venueType.${slug}`) ? t(`venueType.${slug}`) : slug,
        ),
        ...inCharacterOrder(venue.characterTags ?? [], (slug) => slug).map((slug) =>
          te(`venueCharacter.${slug}`) ? t(`venueCharacter.${slug}`) : slug,
        ),
        venue.capacity
          ? t('venues.capacity', {
              count: new Intl.NumberFormat(intlLocale(), { maximumSignificantDigits: 2 }).format(
                venue.capacity,
              ),
            })
          : '',
      ]
        .filter(Boolean)
        .join(' · '),

    /**
     * A list tile's count line as its parts, ["12 events in the next 30 days", "220 upcoming overall"] (#2737),
     * which the tile keeps whole so a narrow one breaks between them. The total alone when nothing is upcoming, or
     * when the response has no 30-day count, as an older BFF sends; `[]` when it has no count at all.
     */
    formatUpcoming: (item: {
      upcomingEventCount?: number
      upcomingNext30DaysCount?: number
    }): string[] => {
      const { upcomingEventCount: upcoming, upcomingNext30DaysCount: next30 } = item
      if (upcoming === undefined) return []
      return next30 === undefined || upcoming === 0
        ? [t('common.upcomingCount', { count: upcoming })]
        : [
            t('common.upcomingNext30', { count: next30 }),
            t('common.upcomingOverall', { count: upcoming }),
          ]
    },

    /**
     * What is said on stage and what a screening is subtitled in, as one meta item: "English",
     * "German & English", "English · German subtitles". `''` when the venue said neither (#2523).
     * The language names come from `Intl`, so a new code needs no catalogue entry.
     */
    formatSpokenLanguage: (spoken?: string[] | null, subtitle?: string | null) => {
      const names = new Intl.DisplayNames(intlLocale(), { type: 'language' })
      const name = (code: string) => names.of(code) ?? code
      return [
        (spoken ?? []).map(name).join(' & '),
        subtitle ? t('events.language.subtitles', { language: name(subtitle) }) : '',
      ]
        .filter(Boolean)
        .join(' · ')
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
