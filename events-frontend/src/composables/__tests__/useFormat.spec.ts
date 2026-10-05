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
