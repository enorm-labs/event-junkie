import { describe, expect, it } from 'vitest'

import type { EventDetail } from '@/api/types'
import {
  berlinToUtc,
  DEFAULT_DURATION_HOURS,
  escapeText,
  eventCalendarSpan,
  eventLocation,
  eventUid,
  foldLine,
  googleCalendarUrl,
  toIcs,
  type CalendarEntry,
} from '@/lib/addToCalendar'

const event: EventDetail = {
  slug: '2026-06-12-lido-test-act',
  title: 'Test Act',
  eventType: 'CONCERT',
  eventDate: '2026-06-12',
  startTime: '20:00',
  venue: { slug: 'lido', name: 'Lido', address: 'Cuvrystr. 7', city: 'Berlin' },
}

const NOW = new Date('2026-06-01T10:00:00Z')

function entryFor(detail: EventDetail): CalendarEntry {
  const span = eventCalendarSpan(detail)
  if (!span) throw new Error('no span')
  return { uid: eventUid(detail.slug ?? ''), title: detail.title ?? '', span }
}

const octets = (line: string) => new TextEncoder().encode(line).length

describe('berlinToUtc', () => {
  it('subtracts two hours in summer and one in winter', () => {
    expect(berlinToUtc('2026-06-12', '20:00').toISOString()).toBe('2026-06-12T18:00:00.000Z')
    expect(berlinToUtc('2026-01-10', '23:00').toISOString()).toBe('2026-01-10T22:00:00.000Z')
  })

  it('reads a time either side of the October switch on its own offset', () => {
    // Berlin falls back at 03:00 CEST on 2026-10-25.
    expect(berlinToUtc('2026-10-24', '23:00').toISOString()).toBe('2026-10-24T21:00:00.000Z')
    expect(berlinToUtc('2026-10-25', '12:00').toISOString()).toBe('2026-10-25T11:00:00.000Z')
  })

  it('reads the day of the March switch on summer time once it has passed', () => {
    expect(berlinToUtc('2026-03-29', '01:00').toISOString()).toBe('2026-03-29T00:00:00.000Z')
    expect(berlinToUtc('2026-03-29', '04:00').toISOString()).toBe('2026-03-29T02:00:00.000Z')
  })

  it('accepts a time with seconds', () => {
    expect(berlinToUtc('2026-06-12', '20:00:00').toISOString()).toBe('2026-06-12T18:00:00.000Z')
  })
})

describe('eventCalendarSpan', () => {
  it('adds the event type default duration when no end is stated, and marks it', () => {
    const span = eventCalendarSpan(event)
    expect(span).toEqual({
      allDay: false,
      start: new Date('2026-06-12T18:00:00Z'),
      end: new Date('2026-06-12T21:00:00Z'),
      startEstimated: false,
      endEstimated: true,
    })
  })

  it('runs a party past midnight on its default', () => {
    expect(DEFAULT_DURATION_HOURS.PARTY).toBe(6)
    const span = eventCalendarSpan({ ...event, eventType: 'PARTY', startTime: '23:00' })
    expect(span).toMatchObject({
      start: new Date('2026-06-12T21:00:00Z'),
      end: new Date('2026-06-13T03:00:00Z'),
      endEstimated: true,
    })
  })

  it('falls back to three hours for a type it does not know', () => {
    const span = eventCalendarSpan({ ...event, eventType: undefined })
    expect(span).toMatchObject({ end: new Date('2026-06-12T21:00:00Z') })
  })

  it('uses a stated end across midnight as stated', () => {
    const span = eventCalendarSpan({
      ...event,
      eventType: 'PARTY',
      startTime: '23:00',
      endDate: '2026-06-13',
      endTime: '08:00',
    })
    expect(span).toEqual({
      allDay: false,
      start: new Date('2026-06-12T21:00:00Z'),
      end: new Date('2026-06-13T06:00:00Z'),
      startEstimated: false,
      endEstimated: false,
    })
  })

  it('starts at the doors when no start is stated', () => {
    const span = eventCalendarSpan({ ...event, startTime: null, doorsTime: '19:00' })
    expect(span).toMatchObject({ start: new Date('2026-06-12T17:00:00Z'), startEstimated: false })
  })

  it('marks the assumed start as an estimate', () => {
    const span = eventCalendarSpan({ ...event, startTime: null, assumedStartTime: '23:00' })
    expect(span).toMatchObject({ start: new Date('2026-06-12T21:00:00Z'), startEstimated: true })
  })

  it('spans whole days when a run of days states no end time', () => {
    const span = eventCalendarSpan({ ...event, eventType: 'EXHIBITION', endDate: '2026-06-30' })
    expect(span).toEqual({ allDay: true, startDate: '2026-06-12', endDateExclusive: '2026-07-01' })
  })

  it('is one whole day when no time is known at all', () => {
    const span = eventCalendarSpan({ ...event, startTime: null })
    expect(span).toEqual({ allDay: true, startDate: '2026-06-12', endDateExclusive: '2026-06-13' })
  })

  it('is null without a date', () => {
    expect(eventCalendarSpan({ ...event, eventDate: undefined })).toBeNull()
  })
})

describe('eventLocation', () => {
  it('joins what is known of the venue and the room', () => {
    expect(eventLocation({ ...event, room: 'Saal' })).toBe('Lido, Saal, Cuvrystr. 7, Berlin')
    expect(eventLocation({ ...event, venue: undefined })).toBeUndefined()
  })
})

describe('escapeText', () => {
  it('escapes backslash, semicolon and comma, and writes line breaks as \\n', () => {
    expect(escapeText('a\\b; c, d\r\ne\nf')).toBe('a\\\\b\\; c\\, d\\ne\\nf')
  })
})

describe('foldLine', () => {
  it('leaves a line of 75 octets alone', () => {
    const line = 'x'.repeat(75)
    expect(foldLine(line)).toBe(line)
  })

  it('folds at 75 octets and continues with a space, 75 octets with it', () => {
    const folded = foldLine('x'.repeat(200)).split('\r\n')
    expect(folded.map(octets)).toEqual([75, 75, 52])
    expect(folded.slice(1).every((line) => line.startsWith(' '))).toBe(true)
    expect(folded.map((line, i) => (i ? line.slice(1) : line)).join('')).toBe('x'.repeat(200))
  })

  it('counts UTF-8 octets and never splits a character', () => {
    const line = `SUMMARY:${'ü'.repeat(60)}`
    const folded = foldLine(line).split('\r\n')
    expect(folded.every((part) => octets(part) <= 75)).toBe(true)
    expect(folded.map((part, i) => (i ? part.slice(1) : part)).join('')).toBe(line)
    expect(folded.join('')).not.toContain('�')
  })
})

describe('toIcs', () => {
  it('writes a VEVENT in UTC with CRLF line endings', () => {
    const ics = toIcs(
      {
        ...entryFor(event),
        location: 'Lido, Cuvrystr. 7, Berlin',
        description: 'Line one\nhttps://event-junkie.de/en/events/2026-06-12-lido-test-act',
        url: 'https://event-junkie.de/en/events/2026-06-12-lido-test-act',
      },
      NOW,
    )
    expect(ics.endsWith('\r\n')).toBe(true)
    expect(ics.replace(/\r\n/g, '')).not.toMatch(/[\r\n]/)
    const lines = ics.replace(/\r\n /g, '').split('\r\n')
    expect(lines).toEqual([
      'BEGIN:VCALENDAR',
      'VERSION:2.0',
      'PRODID:-//Event Junkie//Event Junkie//EN',
      'CALSCALE:GREGORIAN',
      'METHOD:PUBLISH',
      'BEGIN:VEVENT',
      'UID:2026-06-12-lido-test-act@event-junkie.de',
      'DTSTAMP:20260601T100000Z',
      'DTSTART:20260612T180000Z',
      'DTEND:20260612T210000Z',
      'SUMMARY:Test Act',
      'LOCATION:Lido\\, Cuvrystr. 7\\, Berlin',
      'DESCRIPTION:Line one\\nhttps://event-junkie.de/en/events/2026-06-12-lido-test-act',
      'URL:https://event-junkie.de/en/events/2026-06-12-lido-test-act',
      'END:VEVENT',
      'END:VCALENDAR',
      '',
    ])
    expect(ics.split('\r\n').every((line) => octets(line) <= 75)).toBe(true)
  })

  it('writes whole days as DATE values with an exclusive end', () => {
    const ics = toIcs(entryFor({ ...event, startTime: null, endDate: '2026-06-14' }), NOW)
    expect(ics).toContain('DTSTART;VALUE=DATE:20260612\r\n')
    expect(ics).toContain('DTEND;VALUE=DATE:20260615\r\n')
  })

  it('leaves out what is not known', () => {
    const ics = toIcs(entryFor(event), NOW)
    expect(ics).not.toContain('LOCATION')
    expect(ics).not.toContain('DESCRIPTION')
  })
})

describe('googleCalendarUrl', () => {
  it('links a timed event in UTC, with the text encoded', () => {
    const url = new URL(
      googleCalendarUrl({ ...entryFor({ ...event, title: 'A & B' }), location: 'Lido, Berlin' }),
    )
    expect(url.origin + url.pathname).toBe('https://calendar.google.com/calendar/render')
    expect(url.searchParams.get('action')).toBe('TEMPLATE')
    expect(url.searchParams.get('text')).toBe('A & B')
    expect(url.searchParams.get('dates')).toBe('20260612T180000Z/20260612T210000Z')
    expect(url.searchParams.get('location')).toBe('Lido, Berlin')
    expect(url.searchParams.has('details')).toBe(false)
  })

  it('links whole days with an exclusive end', () => {
    const url = new URL(googleCalendarUrl(entryFor({ ...event, startTime: null })))
    expect(url.searchParams.get('dates')).toBe('20260612/20260613')
  })
})
