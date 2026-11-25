import { afterEach, describe, expect, it, vi } from 'vitest'

import {
  eventLabel,
  formatDate,
  formatDayRange,
  formatDaysAgo,
  formatShortDate,
  humaniseEventType,
  hasStarted,
  isPastEvent,
  isOnNow,
  isRunningEvent,
  todayIso,
  tomorrowIso,
  venueLocationLine,
  yesterdayIso,
  type EventSpan,
} from '@/lib/format'

describe('formatDate locale handling', () => {
  it('uses day-before-month for English, not US ordering', () => {
    // Regression guard: a bare UI locale `en` resolves to US conventions in Intl ("Jun 12, 2026");
    // INTL_LOCALES maps it to a formatting tag.
    expect(formatDate('2026-06-12', 'en-GB')).toBe('Fri, 12 Jun 2026')
  })

  it('formats German dates in German', () => {
    expect(formatDate('2026-06-12', 'de-DE')).toContain('12. Juni 2026')
  })

  it('returns the input unchanged when it is not an ISO date', () => {
    expect(formatDate('not-a-date', 'en-GB')).toBe('not-a-date')
  })
})

describe('formatShortDate', () => {
  it('drops the year inside the current year', () => {
    expect(formatShortDate('2026-06-12', 'en-GB', 2026)).toBe('Fri 12 Jun')
    expect(formatShortDate('2026-06-12', 'de-DE', 2026)).toContain('12. Juni')
    expect(formatShortDate('2026-06-12', 'de-DE', 2026)).not.toContain('2026')
  })

  it('keeps the year for any other year', () => {
    // A venue's past events go back years. Without this, a 2024 gig reads as one from this June.
    expect(formatShortDate('2024-06-12', 'en-GB', 2026)).toBe('Wed, 12 Jun 2024')
  })

  it('returns the input unchanged when it is not an ISO date', () => {
    expect(formatShortDate('not-a-date', 'en-GB', 2026)).toBe('not-a-date')
  })

  it('is empty for no date at all', () => {
    expect(formatShortDate(null, 'en-GB', 2026)).toBe('')
  })
})

describe('humaniseEventType', () => {
  it('reads a single-word constant as a capitalised word', () => {
    expect(humaniseEventType('CONCERT')).toBe('Concert')
  })

  it('turns an underscored constant into a sentence-case phrase', () => {
    expect(humaniseEventType('CLUB_NIGHT')).toBe('Club night')
  })

  it('is empty for a missing type, so callers never render a stray label', () => {
    expect(humaniseEventType(null)).toBe('')
    expect(humaniseEventType(undefined)).toBe('')
  })
})

describe('eventLabel', () => {
  it('joins the title and venue with an @', () => {
    expect(eventLabel('The Adicts', 'Lido')).toBe('The Adicts @ Lido')
  })

  it('omits the @ when the venue is missing, rather than dangling it', () => {
    expect(eventLabel('The Adicts', undefined)).toBe('The Adicts')
    expect(eventLabel('The Adicts', null)).toBe('The Adicts')
    expect(eventLabel('The Adicts', '')).toBe('The Adicts')
  })

  it('degrades to the venue alone when the title is missing, never a leading @', () => {
    expect(eventLabel(undefined, 'Lido')).toBe('Lido')
    expect(eventLabel('', 'Lido')).toBe('Lido')
    expect(eventLabel(undefined, undefined)).toBe('')
  })
})

describe('venueLocationLine', () => {
  it('reads as the street address then the district label', () => {
    expect(venueLocationLine({ address: 'Holzmarktstr. 25', district: 'friedrichshain' })).toBe(
      'Holzmarktstr. 25 · Friedrichshain',
    )
  })

  it('drops a part the venue lacks with its separator', () => {
    expect(venueLocationLine({ address: 'Cuvrystr. 7', district: null })).toBe('Cuvrystr. 7')
    expect(venueLocationLine({ address: null, district: 'kreuzberg' })).toBe('Kreuzberg')
    expect(venueLocationLine({})).toBe('')
  })

  it('is empty when no venue is selected, so a panel renders no stray line', () => {
    expect(venueLocationLine(null)).toBe('')
    expect(venueLocationLine(undefined)).toBe('')
  })
})

describe('date helpers', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  it('tomorrowIso is the calendar day after todayIso', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-07T12:00:00Z'))

    expect(todayIso()).toBe('2026-07-07')
    expect(tomorrowIso()).toBe('2026-07-08')
  })

  it('rolls over month and year boundaries', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-12-31T12:00:00Z'))

    expect(tomorrowIso()).toBe('2027-01-01')
  })

  it('advances by one calendar day across the spring DST shift', () => {
    // Europe/Berlin springs forward on 2026-03-29; adding a calendar day must still land on the 29th.
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-03-28T12:00:00Z'))

    expect(tomorrowIso()).toBe('2026-03-29')
  })

  it('yesterdayIso is the calendar day before todayIso, across a year boundary', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-07T12:00:00Z'))
    expect(yesterdayIso()).toBe('2026-07-06')

    vi.setSystemTime(new Date('2026-01-01T12:00:00Z'))
    expect(yesterdayIso()).toBe('2025-12-31')
  })
})

describe('isPastEvent', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  const on = (eventDate: string, rest: Partial<EventSpan> = {}) => ({ eventDate, ...rest })

  it("treats today's event as still to come, and yesterday's as past", () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-07T12:00:00Z'))

    // The boundary the BFF and the importer both use: `>= today` is upcoming.
    expect(isPastEvent(on('2026-07-06'))).toBe(true)
    expect(isPastEvent(on('2026-07-07'))).toBe(false)
    expect(isPastEvent(on('2026-07-08'))).toBe(false)
  })

  it('ends on the stated end date when there is one (ADR-029)', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-07T12:00:00Z'))

    // A Friday-to-Monday weekender on its Tuesday: over. On its Sunday: not.
    expect(isPastEvent(on('2026-07-03', { endDate: '2026-07-06' }))).toBe(true)
    expect(isPastEvent(on('2026-07-03', { endDate: '2026-07-07' }))).toBe(false)
    expect(isRunningEvent(on('2026-07-03', { endDate: '2026-07-07' }))).toBe(true)
    // Started today: live, not running-since.
    expect(isRunningEvent(on('2026-07-07', { endDate: '2026-07-09' }))).toBe(false)
    expect(isRunningEvent(on('2026-07-06'))).toBe(false)
  })

  it('ends at the stated end time when the end is today', () => {
    vi.useFakeTimers()
    // 12:00 Berlin on the 7th (10:00 UTC in July).
    vi.setSystemTime(new Date('2026-07-07T10:00:00Z'))

    // A club night that ended at 04:00 this morning is over, and not "running since" yesterday.
    const night = on('2026-07-06', {
      startTime: '23:00:00',
      endDate: '2026-07-07',
      endTime: '04:00:00',
    })
    expect(isPastEvent(night)).toBe(true)
    expect(isRunningEvent(night)).toBe(false)
    // An end at midnight sharp is over the whole next day.
    expect(isPastEvent(on('2026-07-06', { endDate: '2026-07-07', endTime: '00:00:00' }))).toBe(true)
    // An end later today is not over yet, and a run ending today with no time lasts all day.
    const later = on('2026-07-06', { endDate: '2026-07-07', endTime: '18:00:00' })
    expect(isPastEvent(later)).toBe(false)
    expect(isRunningEvent(later)).toBe(true)
    expect(isPastEvent(on('2026-07-01', { endDate: '2026-07-07' }))).toBe(false)
    // A show today with a stated end this evening is over once the clock passes it.
    expect(
      isPastEvent(
        on('2026-07-07', { startTime: '20:00:00', endDate: '2026-07-07', endTime: '23:00:00' }),
      ),
    ).toBe(false)
    vi.setSystemTime(new Date('2026-07-07T21:30:00Z'))
    expect(
      isPastEvent(
        on('2026-07-07', { startTime: '20:00:00', endDate: '2026-07-07', endTime: '23:00:00' }),
      ),
    ).toBe(true)
  })

  it('keeps last night until six in the morning when it started late (#299)', () => {
    vi.useFakeTimers()
    // 03:00 Berlin on the 7th (01:00 UTC in July).
    vi.setSystemTime(new Date('2026-07-07T01:00:00Z'))

    expect(isPastEvent(on('2026-07-06', { startTime: '23:00:00' }))).toBe(false)
    expect(isPastEvent(on('2026-07-06', { doorsTime: '22:00:00' }))).toBe(false)
    // No time at all: the BFF's slot for a party is 23:00, and no slot at all still counts as a night.
    expect(isPastEvent(on('2026-07-06', { assumedStartTime: '23:00:00' }))).toBe(false)
    expect(isPastEvent(on('2026-07-06'))).toBe(false)
    // A 20:00 gig is over at midnight, and a stated end is the venue's word.
    expect(isPastEvent(on('2026-07-06', { startTime: '20:00:00' }))).toBe(true)
    expect(isPastEvent(on('2026-07-06', { startTime: '23:00:00', endDate: '2026-07-06' }))).toBe(
      true,
    )
    // The night before last gets no grace.
    expect(isPastEvent(on('2026-07-05', { startTime: '23:00:00' }))).toBe(true)
    // Running, since it started yesterday and is not over.
    expect(isRunningEvent(on('2026-07-06', { startTime: '23:00:00' }))).toBe(true)

    // 06:00 Berlin: over.
    vi.setSystemTime(new Date('2026-07-07T04:00:00Z'))
    expect(isPastEvent(on('2026-07-06', { startTime: '23:00:00' }))).toBe(true)
  })

  it('is false for a missing date rather than throwing', () => {
    expect(isPastEvent({ eventDate: null })).toBe(false)
    expect(isPastEvent({})).toBe(false)
    expect(isPastEvent(on(''))).toBe(false)
  })

  it('reads the Berlin calendar day, not UTC', () => {
    // 23:30 UTC on the 6th is already the 7th in Berlin, so a 20:00 show on the 6th has passed.
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-06T23:30:00Z'))

    expect(isPastEvent(on('2026-07-06', { startTime: '20:00:00' }))).toBe(true)
  })
})

describe('isOnNow', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  const on = (eventDate: string, rest: Partial<EventSpan> = {}) => ({ eventDate, ...rest })

  it("starts today's event at its effective start, Berlin time", () => {
    vi.useFakeTimers()
    // 20:30 Berlin on the 7th (18:30 UTC in July).
    vi.setSystemTime(new Date('2026-07-07T18:30:00Z'))

    expect(isOnNow(on('2026-07-07', { startTime: '20:00:00' }))).toBe(true)
    expect(isOnNow(on('2026-07-07', { startTime: '20:30:00' }))).toBe(true)
    expect(isOnNow(on('2026-07-07', { startTime: '21:00:00' }))).toBe(false)
    // Doors, then the BFF's slot, stand in for a missing start.
    expect(isOnNow(on('2026-07-07', { doorsTime: '19:00:00', startTime: '21:00:00' }))).toBe(false)
    expect(isOnNow(on('2026-07-07', { doorsTime: '19:00:00' }))).toBe(true)
    expect(isOnNow(on('2026-07-07', { assumedStartTime: '23:00:00' }))).toBe(false)
    // Tomorrow's event has not started, whatever its time.
    expect(isOnNow(on('2026-07-08', { startTime: '00:00:00' }))).toBe(false)
  })

  it('is false for today with no time at all, since nothing says it has started', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-07T18:30:00Z'))

    expect(isOnNow(on('2026-07-07'))).toBe(false)
  })

  it('ends with a stated end time today', () => {
    vi.useFakeTimers()
    // 23:30 Berlin.
    vi.setSystemTime(new Date('2026-07-07T21:30:00Z'))

    const show = on('2026-07-07', {
      startTime: '20:00:00',
      endDate: '2026-07-07',
      endTime: '23:00:00',
    })
    expect(isOnNow(show)).toBe(false)
  })

  it("keeps last night's party on until the grace ends at six", () => {
    vi.useFakeTimers()
    // 03:00 Berlin on the 7th.
    vi.setSystemTime(new Date('2026-07-07T01:00:00Z'))

    expect(isOnNow(on('2026-07-06', { startTime: '23:00:00' }))).toBe(true)
    // A 20:00 gig was over at midnight.
    expect(isOnNow(on('2026-07-06', { startTime: '20:00:00' }))).toBe(false)

    // 06:00 Berlin: past the grace window.
    vi.setSystemTime(new Date('2026-07-07T04:00:00Z'))
    expect(isOnNow(on('2026-07-06', { startTime: '23:00:00' }))).toBe(false)
  })

  it("counts a weekender's second night as on now", () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-07-07T12:00:00Z'))

    expect(isOnNow(on('2026-07-05', { endDate: '2026-07-08' }))).toBe(true)
    expect(isOnNow(on('2026-07-03', { endDate: '2026-07-06' }))).toBe(false)
  })

  describe('with no stated end (#5510)', () => {
    const TODAY = '2026-07-07'

    /** Sets the clock to a Berlin wall time in July, when Berlin is UTC+2. */
    function berlin(date: string, time: string) {
      vi.useFakeTimers()
      vi.setSystemTime(new Date(`${date}T${time}:00+02:00`))
    }

    it('ends a morning show after the hours of its type, and keeps it in the list', () => {
      const show = on(TODAY, { startTime: '09:30:00', eventType: 'SHOW' })

      berlin(TODAY, '11:29')
      expect(isOnNow(show)).toBe(true)
      berlin(TODAY, '11:30')
      expect(isOnNow(show)).toBe(false)
      berlin(TODAY, '20:00')
      expect(isOnNow(show)).toBe(false)
      expect(isPastEvent(show)).toBe(false)
      expect(hasStarted(show)).toBe(true)
    })

    it('takes the fallback for an unknown type and for none', () => {
      berlin(TODAY, '17:59')
      expect(isOnNow(on(TODAY, { startTime: '15:00', eventType: 'BALLET' }))).toBe(true)
      expect(isOnNow(on(TODAY, { startTime: '15:00' }))).toBe(true)
      berlin(TODAY, '18:00')
      expect(isOnNow(on(TODAY, { startTime: '15:00', eventType: 'BALLET' }))).toBe(false)
      expect(isOnNow(on(TODAY, { startTime: '15:00' }))).toBe(false)
    })

    it('counts from doors, then from the assumed slot, where the start is missing', () => {
      berlin(TODAY, '21:30')
      const concert = { eventType: 'CONCERT' }
      expect(isOnNow(on(TODAY, { ...concert, doorsTime: '18:30' }))).toBe(false)
      expect(isOnNow(on(TODAY, { ...concert, doorsTime: '18:30', startTime: '19:00' }))).toBe(true)
      expect(isOnNow(on(TODAY, { ...concert, assumedStartTime: '18:30' }))).toBe(false)
      expect(isOnNow(on(TODAY, { ...concert, assumedStartTime: '20:00' }))).toBe(true)
      expect(isOnNow(on(TODAY, concert))).toBe(false)
    })

    it('lets a stated end win, later or earlier than the assumed one', () => {
      const stated = (endTime: string) =>
        on(TODAY, { startTime: '09:30', endDate: TODAY, endTime, eventType: 'SHOW' })

      berlin(TODAY, '13:00')
      expect(isOnNow(stated('14:00'))).toBe(true)
      berlin(TODAY, '10:30')
      expect(isOnNow(stated('10:15'))).toBe(false)
      // A stated end tomorrow morning.
      berlin(TODAY, '23:30')
      const night = { startTime: '15:00', endDate: '2026-07-08', endTime: '06:00' }
      expect(isOnNow(on(TODAY, { ...night, eventType: 'PARTY' }))).toBe(true)
    })

    it('assumes an end where only the end date is stated, and it is the same day', () => {
      berlin(TODAY, '20:00')
      expect(isOnNow(on(TODAY, { startTime: '09:30', endDate: TODAY }))).toBe(false)
    })

    it.each([
      ['its first day', TODAY, '2026-07-09'],
      ['a middle day', '2026-07-06', '2026-07-08'],
      ['its last day', '2026-07-05', TODAY],
    ])('does not shorten a multi-day event on %s', (_, eventDate, endDate) => {
      berlin(TODAY, '23:00')
      expect(isOnNow(on(eventDate, { endDate, startTime: '10:00', eventType: 'SHOW' }))).toBe(true)
    })

    it.each(['PARTY', 'QUIZ', 'CONCERT', undefined])(
      'keeps a 22:00 start of type %s on until six, on both sides of midnight',
      (eventType) => {
        const night = on(TODAY, { startTime: '22:00:00', eventType })
        berlin(TODAY, '23:59')
        expect(isOnNow(night)).toBe(true)
        berlin('2026-07-08', '00:00')
        expect(isOnNow(night)).toBe(true)
        berlin('2026-07-08', '05:59')
        expect(isOnNow(night)).toBe(true)
        berlin('2026-07-08', '06:00')
        expect(isOnNow(night)).toBe(false)
      },
    )

    it('ends an earlier start at midnight where its assumed end is later', () => {
      const party = on(TODAY, { startTime: '21:59', eventType: 'PARTY' })
      berlin(TODAY, '23:59')
      expect(isOnNow(party)).toBe(true)
      berlin('2026-07-08', '00:00')
      expect(isOnNow(party)).toBe(false)
      expect(isPastEvent(party)).toBe(true)
    })

    it('ends a start after midnight on its own day', () => {
      const party = on(TODAY, { startTime: '00:30', eventType: 'PARTY' })
      berlin(TODAY, '06:29')
      expect(isOnNow(party)).toBe(true)
      berlin(TODAY, '06:30')
      expect(isOnNow(party)).toBe(false)
    })

    it.each(['SCHEDULED', 'RELOCATED', 'CANCELLED', 'POSTPONED'])(
      'ends a %s event by the same clock',
      (status) => {
        const show = { ...on(TODAY, { startTime: '09:30', eventType: 'SHOW' }), status }
        berlin(TODAY, '10:00')
        expect(isOnNow(show)).toBe(true)
        berlin(TODAY, '20:00')
        expect(isOnNow(show)).toBe(false)
      },
    )
  })
})

describe('formatDaysAgo', () => {
  it('counts Berlin days, not 24-hour spans', () => {
    // 23:30 UTC on the 8th is 01:30 on the 9th in Berlin: today, not yesterday.
    expect(formatDaysAgo('2026-10-08T23:30:00Z', 'en-GB', '2026-10-09')).toBe('today')
    expect(formatDaysAgo('2026-10-08T21:30:00Z', 'en-GB', '2026-10-09')).toBe('yesterday')
    expect(formatDaysAgo('2026-10-06T12:00:00+02:00', 'en-GB', '2026-10-09')).toBe('3 days ago')
  })

  it('speaks German', () => {
    expect(formatDaysAgo('2026-10-08T12:00:00+02:00', 'de-DE', '2026-10-09')).toBe('gestern')
    expect(formatDaysAgo('2026-10-05T12:00:00+02:00', 'de-DE', '2026-10-09')).toBe('vor 4 Tagen')
  })

  it('reads a change seen after today, by a skewed clock, as today', () => {
    expect(formatDaysAgo('2026-10-10T12:00:00+02:00', 'en-GB', '2026-10-09')).toBe('today')
  })
})

describe('formatDayRange', () => {
  const plain = (text: string) => text.replace(/\s+/g, ' ')

  it('names the days of one month once', () => {
    expect(plain(formatDayRange('2027-10-02', '2027-10-10', 'en-GB'))).toBe('2 – 10 October')
    expect(plain(formatDayRange('2027-10-02', '2027-10-10', 'de-DE'))).toBe('2.–10. Oktober')
  })

  it('names both months of a range across a month end', () => {
    expect(plain(formatDayRange('2027-06-28', '2027-07-02', 'de-DE'))).toBe('28. Juni – 2. Juli')
  })

  it('names one day alone', () => {
    expect(formatDayRange('2027-06-21', '2027-06-21', 'en-GB')).toBe('21 June')
  })
})
