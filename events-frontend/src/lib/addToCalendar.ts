import type { EventDetail } from '@/api/types'
import { addDays } from '@/lib/format'
import { SITE_URL } from '@/lib/seo'

/**
 * A calendar entry for an event, as an RFC 5545 `.ics` file and as a Google Calendar template
 * link. Both are built in the browser from the data the page already holds: no request, no
 * storage, and nothing loaded from Google — its link is an outbound link like a ticket link.
 */

/**
 * Hours an event lasts when the venue stated no end, per event type. An estimate, and the entry
 * says so: a club night shown as a 30-minute slot at 23:00 is worse than a guess that says it is one.
 */
export const DEFAULT_DURATION_HOURS: Readonly<Record<string, number>> = {
  CONCERT: 3,
  FESTIVAL: 8,
  PARTY: 6,
  QUIZ: 2,
  SHOW: 2,
  COMEDY: 2,
  SCREENING: 2,
  EXHIBITION: 3,
  READING: 2,
  OTHER: 3,
}
const FALLBACK_DURATION_HOURS = 3
const HOUR_MS = 3_600_000

/** A span with times, in UTC, or whole days with an exclusive end, as RFC 5545 counts them. */
export type CalendarSpan =
  | { allDay: false; start: Date; end: Date; startEstimated: boolean; endEstimated: boolean }
  | { allDay: true; startDate: string; endDateExclusive: string }

export type CalendarEntry = {
  uid: string
  title: string
  span: CalendarSpan
  location?: string
  description?: string
  url?: string
}

/** Milliseconds Berlin is ahead of UTC at the instant `utcMs`. */
function berlinOffsetMs(utcMs: number): number {
  const parts = new Intl.DateTimeFormat('en-US', {
    timeZone: 'Europe/Berlin',
    hourCycle: 'h23',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    second: '2-digit',
  }).formatToParts(new Date(utcMs))
  const part = (type: string) => Number(parts.find((p) => p.type === type)?.value)
  const local = Date.UTC(
    part('year'),
    part('month') - 1,
    part('day'),
    part('hour'),
    part('minute'),
    part('second'),
  )
  return local - utcMs
}

/**
 * The instant a Berlin wall-clock time names, DST included. The second pass corrects a first guess
 * taken on the wrong side of a switch; a time inside the spring-forward gap lands an hour later.
 */
export function berlinToUtc(isoDate: string, time: string): Date {
  const [year, month, day] = isoDate.split('-').map(Number)
  const [hour, minute] = time.split(':').map(Number)
  const wallMs = Date.UTC(year ?? 0, (month ?? 1) - 1, day ?? 1, hour ?? 0, minute ?? 0)
  const guess = wallMs - berlinOffsetMs(wallMs)
  return new Date(wallMs - berlinOffsetMs(guess))
}

/**
 * When an event happens, for a calendar. A stated end is used as stated; a single day with a start
 * and no end gets the type's default duration, marked as an estimate. A span of days with no end
 * time is whole days, as the site's own calendar draws it (#1405), and so is an event with no time.
 */
export function eventCalendarSpan(event: EventDetail): CalendarSpan | null {
  if (!event.eventDate) return null
  const stated = event.startTime ?? event.doorsTime
  const start = stated ?? event.assumedStartTime
  if (!start || (event.endDate && !event.endTime)) {
    return {
      allDay: true,
      startDate: event.eventDate,
      endDateExclusive: addDays(event.endDate ?? event.eventDate, 1),
    }
  }
  const startUtc = berlinToUtc(event.eventDate, start)
  const statedEnd =
    event.endDate && event.endTime ? berlinToUtc(event.endDate, event.endTime) : null
  if (statedEnd && statedEnd > startUtc) {
    return {
      allDay: false,
      start: startUtc,
      end: statedEnd,
      startEstimated: !stated,
      endEstimated: false,
    }
  }
  const hours = DEFAULT_DURATION_HOURS[event.eventType ?? ''] ?? FALLBACK_DURATION_HOURS
  return {
    allDay: false,
    start: startUtc,
    end: new Date(startUtc.getTime() + hours * HOUR_MS),
    startEstimated: !stated,
    endEstimated: true,
  }
}

/** `Venue, Room, Street 1, City` — whatever of it is known. */
export function eventLocation(event: EventDetail): string | undefined {
  const venue = event.venue
  const parts = [venue?.name, event.room, venue?.address, venue?.city].filter(Boolean)
  return parts.length ? parts.join(', ') : undefined
}

/** `20261003T210000Z`. */
function utcStamp(date: Date): string {
  return date
    .toISOString()
    .replace(/[-:]/g, '')
    .replace(/\.\d{3}/, '')
}

/** `20261003`. */
function dateStamp(isoDate: string): string {
  return isoDate.replace(/-/g, '')
}

/** An RFC 5545 TEXT value: backslash, semicolon and comma escaped, every line break as `\n`. */
export function escapeText(value: string): string {
  return value
    .replace(/\\/g, '\\\\')
    .replace(/;/g, '\\;')
    .replace(/,/g, '\\,')
    .replace(/\r\n|\r|\n/g, '\\n')
}

const encoder = new TextEncoder()

/**
 * Folds a content line at 75 octets, continuing with a leading space (RFC 5545 §3.1). Counts
 * UTF-8 bytes, not characters, and never splits one character across two lines.
 */
export function foldLine(line: string): string {
  const lines: string[] = []
  let current = ''
  let octets = 0
  for (const char of line) {
    const size = encoder.encode(char).length
    // A continuation line's leading space is one of its 75 octets.
    const limit = lines.length ? 74 : 75
    if (octets + size > limit) {
      lines.push(current)
      current = ''
      octets = 0
    }
    current += char
    octets += size
  }
  lines.push(current)
  return lines.join('\r\n ')
}

/** The entry as an `.ics` file: CRLF line endings, every line folded, times in UTC. */
export function toIcs(entry: CalendarEntry, now: Date = new Date()): string {
  const { span } = entry
  const when = span.allDay
    ? [
        `DTSTART;VALUE=DATE:${dateStamp(span.startDate)}`,
        `DTEND;VALUE=DATE:${dateStamp(span.endDateExclusive)}`,
      ]
    : [`DTSTART:${utcStamp(span.start)}`, `DTEND:${utcStamp(span.end)}`]
  const lines = [
    'BEGIN:VCALENDAR',
    'VERSION:2.0',
    'PRODID:-//Event Junkie//Event Junkie//EN',
    'CALSCALE:GREGORIAN',
    'METHOD:PUBLISH',
    'BEGIN:VEVENT',
    `UID:${entry.uid}`,
    `DTSTAMP:${utcStamp(now)}`,
    ...when,
    `SUMMARY:${escapeText(entry.title)}`,
    ...(entry.location ? [`LOCATION:${escapeText(entry.location)}`] : []),
    ...(entry.description ? [`DESCRIPTION:${escapeText(entry.description)}`] : []),
    ...(entry.url ? [`URL:${entry.url}`] : []),
    'END:VEVENT',
    'END:VCALENDAR',
  ]
  return lines.map(foldLine).join('\r\n') + '\r\n'
}

/**
 * A Google Calendar template link. Times go as UTC, which needs no zone; `ctz` only sets the zone
 * Google displays them in. Whole days take an exclusive end, as in the `.ics`.
 */
export function googleCalendarUrl(entry: CalendarEntry): string {
  const { span } = entry
  const dates = span.allDay
    ? `${dateStamp(span.startDate)}/${dateStamp(span.endDateExclusive)}`
    : `${utcStamp(span.start)}/${utcStamp(span.end)}`
  const params: [string, string | undefined][] = [
    ['action', 'TEMPLATE'],
    ['text', entry.title],
    ['dates', dates],
    ['ctz', 'Europe/Berlin'],
    ['details', entry.description],
    ['location', entry.location],
  ]
  const query = params
    .filter((param): param is [string, string] => !!param[1])
    // The slash between the two dates stays literal; the rest is encoded.
    .map(([key, value]) => `${key}=${key === 'dates' ? value : encodeURIComponent(value)}`)
    .join('&')
  return `https://calendar.google.com/calendar/render?${query}`
}

/** The UID a calendar recognises the event by when the same file is imported twice. */
export function eventUid(slug: string): string {
  return `${slug}@${new URL(SITE_URL).host}`
}
