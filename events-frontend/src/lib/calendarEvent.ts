import type { EventInput } from '@fullcalendar/vue3'

import type { EventSummary } from '@/api/types'
import { addDays, isPastEvent } from '@/lib/format'
import { isLiveNow } from '@/lib/longRuns'

/**
 * The calendar's entry for an event. A span with both times is a timed block from start to end;
 * the start falls back to the doors time, then to the assumed slot, which is marked `estimated`
 * so the cell shows it as an estimate, never as a fact (#2927). A span without a time is an
 * all-day bar from the opening day through the closing day (#1405). Past
 * and live are the same classes EventCard uses, off the same Berlin clock. `localePath` is
 * `useLocalePath()`'s: the link is a real `href` a crawler follows, and an unprefixed path is a 404.
 */
/**
 * Whether the calendar draws the event across more than one day: a run with a later end date,
 * unless it is a night ending before EventCalendar's `nextDayThreshold` (09:00) the next morning.
 */
export function spansDays(event: Pick<EventSummary, 'eventDate' | 'endDate' | 'endTime'>): boolean {
  if (!event.eventDate || !event.endDate || event.endDate <= event.eventDate) return false
  return !(
    event.endDate === addDays(event.eventDate, 1) &&
    !!event.endTime &&
    event.endTime < '09:00'
  )
}

export function toCalendarInput(
  event: EventSummary,
  localePath: (path: string) => string,
): EventInput {
  const statedStart = event.startTime ?? event.doorsTime
  // A run's bar keeps no guessed hour: "~09:00" across six weeks of an exhibition says nothing.
  const estimated = !statedStart && !!event.assumedStartTime && !spansDays(event)
  const startTime = statedStart ?? (estimated ? event.assumedStartTime : undefined)
  const timed = !!startTime && !!event.endTime
  const end = timed
    ? `${event.endDate ?? event.eventDate}T${event.endTime}`
    : event.endDate
      ? addDays(event.endDate, 1) // FullCalendar's all-day `end` is exclusive.
      : undefined
  return {
    title: event.title ?? '',
    start: startTime ? `${event.eventDate}T${startTime}` : event.eventDate,
    ...(end ? { end } : {}),
    url: localePath(`/events/${event.slug}`),
    // Paging back a month already returned past events; this is what tells them apart. An event
    // on now gets the same "live" mark as EventCard, by the same rule.
    // v7 reads `className` (one string); the v6 `classNames` array is ignored without a warning.
    className: isPastEvent(event)
      ? 'fc-event-past'
      : isLiveNow(event)
        ? 'fc-event-live'
        : undefined,
    // `venue` backs the calendar's hover tooltip, where the clipped title is spelled out.
    // `multiDay` orders a bar after the day's own events (`eventOrder` in EventCalendar, #4655).
    // `estimated` draws the time as the cards do, `~20:00`, with the hint behind it.
    extendedProps: {
      slug: event.slug,
      venue: event.venue?.name,
      multiDay: spansDays(event) ? 1 : 0,
      ...(estimated ? { estimated: true } : {}),
    },
  }
}
