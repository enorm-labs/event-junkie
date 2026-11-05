import type { EventInput } from '@fullcalendar/vue3'

import type { EventSummary } from '@/api/types'
import { addDays, isPastEvent } from '@/lib/format'
import { isLiveNow } from '@/lib/longRuns'

/**
 * The calendar's entry for an event. A span with both times is a timed block from start to end;
 * the start falls back to the doors time, as the list does, but not to the assumed slot (#2927);
 * a span without is an all-day bar from the opening day through the closing day (#1405). Past
 * and live are the same classes EventCard uses, off the same Berlin clock. `localePath` is
 * `useLocalePath()`'s: the link is a real `href` a crawler follows, and an unprefixed path is a 404.
 */
export function toCalendarInput(
  event: EventSummary,
  localePath: (path: string) => string,
): EventInput {
  const startTime = event.startTime ?? event.doorsTime
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
    extendedProps: { slug: event.slug, venue: event.venue?.name },
  }
}
