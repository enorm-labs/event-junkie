import type { EventInput } from '@fullcalendar/vue3'

import type { EventSummary } from '@/api/types'
import { isPastEvent, isRunningEvent, todayIso } from '@/lib/format'

/** The ISO date one day after `isoDate`, for FullCalendar's exclusive all-day `end`. */
function dayAfter(isoDate: string): string {
  const next = new Date(`${isoDate}T12:00:00Z`)
  next.setUTCDate(next.getUTCDate() + 1)
  return next.toISOString().slice(0, 10)
}

/**
 * The calendar's entry for an event. A span with both times is a timed block from start to end;
 * a span without is an all-day bar from the opening day through the closing day (#1405). Past
 * and live are the same classes EventCard uses, off the same Berlin clock.
 */
export function toCalendarInput(event: EventSummary): EventInput {
  const timed = !!event.startTime && !!event.endTime
  const end = timed
    ? `${event.endDate ?? event.eventDate}T${event.endTime}`
    : event.endDate
      ? dayAfter(event.endDate)
      : undefined
  return {
    title: event.title ?? '',
    start: event.startTime ? `${event.eventDate}T${event.startTime}` : event.eventDate,
    ...(end ? { end } : {}),
    url: `/events/${event.slug}`,
    // Paging back a month already returned past events; this is what tells them apart. Today's
    // get the same "live" mark as EventCard, and a run is live on every day it is on.
    // v7 reads `className` (one string); the v6 `classNames` array is ignored without a warning.
    className: isPastEvent(event)
      ? 'fc-event-past'
      : event.eventDate === todayIso() || isRunningEvent(event)
        ? 'fc-event-live'
        : undefined,
    // `venue` backs the calendar's hover tooltip, where the clipped title is spelled out.
    extendedProps: { slug: event.slug, venue: event.venue?.name },
  }
}
