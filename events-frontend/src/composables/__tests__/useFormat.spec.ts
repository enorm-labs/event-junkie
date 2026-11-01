import { mount } from '@vue/test-utils'
import { defineComponent, h } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'

import { useFormat } from '@/composables/useFormat'
import { i18n } from '@/i18n'

function format(): ReturnType<typeof useFormat> {
  let formatters!: ReturnType<typeof useFormat>
  mount(
    defineComponent({
      setup() {
        formatters = useFormat()
        return () => h('div')
      },
    }),
  )
  return formatters
}

describe('formatUpcoming', () => {
  afterEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('names the noun on the 30-day part and says the total includes it (#2737)', () => {
    const { formatUpcoming } = format()

    expect(formatUpcoming({ upcomingEventCount: 220, upcomingNext30DaysCount: 23 })).toEqual([
      '23 events in the next 30 days',
      '220 upcoming overall',
    ])
    expect(formatUpcoming({ upcomingEventCount: 5, upcomingNext30DaysCount: 1 })).toEqual([
      '1 event in the next 30 days',
      '5 upcoming overall',
    ])
    expect(formatUpcoming({ upcomingEventCount: 5, upcomingNext30DaysCount: 0 })).toEqual([
      'No events in the next 30 days',
      '5 upcoming overall',
    ])
  })

  it('says it in German, with "insgesamt" before the number', () => {
    i18n.global.locale.value = 'de'
    const { formatUpcoming } = format()

    expect(formatUpcoming({ upcomingEventCount: 220, upcomingNext30DaysCount: 23 })).toEqual([
      '23 Events in den nächsten 30 Tagen',
      'insgesamt 220 anstehend',
    ])
    expect(formatUpcoming({ upcomingEventCount: 5, upcomingNext30DaysCount: 1 })).toEqual([
      '1 Event in den nächsten 30 Tagen',
      'insgesamt 5 anstehend',
    ])
    expect(formatUpcoming({ upcomingEventCount: 5, upcomingNext30DaysCount: 0 })).toEqual([
      'Keine Events in den nächsten 30 Tagen',
      'insgesamt 5 anstehend',
    ])
  })

  it('gives the total alone when nothing is upcoming or the 30-day count is missing', () => {
    const { formatUpcoming } = format()

    expect(formatUpcoming({ upcomingEventCount: 0, upcomingNext30DaysCount: 0 })).toEqual([
      'No upcoming events',
    ])
    expect(formatUpcoming({ upcomingEventCount: 220 })).toEqual(['220 upcoming events'])
  })

  it('gives nothing without a count', () => {
    expect(format().formatUpcoming({})).toEqual([])
  })
})

describe('formatEventStatus', () => {
  afterEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('names the house and the new date of a show that moved both, and the house alone otherwise (#3613)', () => {
    const { formatEventStatus } = format()

    expect(formatEventStatus('RELOCATED', 'Hole 44', null, '2027-04-13')).toBe(
      'Moved to Hole 44, 13 April 2027',
    )
    expect(formatEventStatus('RELOCATED', 'Hole 44')).toBe('Moved to Hole 44')
    expect(formatEventStatus('RELOCATED', null, null, '2027-04-13')).toBe('Relocated')
  })

  it("names the date a postponed show moved to: the linked row's, else the note's (#4334)", () => {
    const { formatEventStatus } = format()

    expect(formatEventStatus('POSTPONED', null, null, '2027-04-13')).toBe(
      'Postponed to Tue, 13 Apr 2027',
    )
    expect(formatEventStatus('POSTPONED', null, '2027-04-14', '2027-04-13')).toBe(
      'Postponed to Wed, 14 Apr 2027',
    )
    expect(formatEventStatus('POSTPONED')).toBe('Postponed')
    // No other status shows the stored date without a house.
    for (const status of ['SCHEDULED', 'CANCELLED', 'RELOCATED'])
      expect(formatEventStatus(status, null, null, '2027-04-13')).not.toContain('2027')
  })

  it("says the note's date of a postponed show in German", () => {
    i18n.global.locale.value = 'de'

    expect(format().formatEventStatus('POSTPONED', null, null, '2027-11-04')).toBe(
      'Verschoben auf Do., 4. Nov. 2027',
    )
  })

  it('says it in German', () => {
    i18n.global.locale.value = 'de'
    const { formatEventStatus } = format()

    expect(formatEventStatus('RELOCATED', 'Hole 44', null, '2027-04-13')).toBe(
      'Verlegt: Hole 44, 13. April 2027',
    )
    expect(formatEventStatus('RELOCATED', 'Hole 44')).toBe('Verlegt: Hole 44')
  })
})
