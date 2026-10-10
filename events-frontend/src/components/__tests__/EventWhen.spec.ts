import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { mount } from '@vue/test-utils'
import EventWhen from '@/components/EventWhen.vue'
import type { EventDetail } from '@/api/types'
import { i18n } from '@/i18n'

/** The event page's When block: one line per time the event has, and none for a time it lacks (#2565). */

const base: EventDetail = { slug: 'a-night', title: 'A Night', eventDate: '2026-06-12' }

function lines(event: EventDetail): string[] {
  return mount(EventWhen, { props: { event } })
    .findAll('p')
    .map((line) => line.text())
}

afterEach(() => {
  i18n.global.locale.value = 'en'
})

describe('EventWhen', () => {
  it('shows the doors and no start when the venue published only the doors', () => {
    const shown = lines({ ...base, doorsTime: '19:00:00' })

    expect(shown).toHaveLength(3)
    expect(shown[1]).toBe('Doors 19:00')
    expect(shown.join(' ')).not.toContain('Start')
  })

  it('shows the start and no doors when the venue published only the start', () => {
    const shown = lines({ ...base, startTime: '21:00:00' })

    expect(shown).toHaveLength(3)
    expect(shown[1]).toBe('Start 21:00')
    expect(shown.join(' ')).not.toContain('Doors')
  })

  it('marks a start we estimated as our estimate', () => {
    const shown = lines({ ...base, assumedStartTime: '23:00:00' })

    expect(shown).toHaveLength(3)
    expect(shown[1]).toBe('Start ~23:00 · our estimate')
  })

  it('does not mark a start the venue published', () => {
    expect(lines({ ...base, startTime: '21:00:00' }).join(' ')).not.toContain('estimate')
  })

  it('shows doors, start and the next-morning end together', () => {
    const shown = lines({
      ...base,
      doorsTime: '20:00:00',
      startTime: '21:00:00',
      endDate: '2026-06-13',
      endTime: '06:00:00',
    })

    expect(shown.slice(1)).toEqual(['Doors 20:00', 'Start 21:00', 'Until 06:00'])
  })

  it('shows the date alone when no time was announced', () => {
    expect(lines(base)).toHaveLength(1)
  })

  it('says the end was not announced when the venue stated a start and no end', () => {
    const shown = lines({ ...base, startTime: '23:00:00' })

    expect(shown.slice(1)).toEqual(['Start 23:00', 'End not announced'])
  })

  it('says it in German on the German page', () => {
    i18n.global.locale.value = 'de'

    expect(lines({ ...base, startTime: '23:00:00' })).toContain('Ende nicht bekannt gegeben')
  })

  it('styles the line muted, like the estimate note', () => {
    const line = mount(EventWhen, { props: { event: { ...base, startTime: '23:00:00' } } })
      .findAll('p')
      .find((p) => p.text() === 'End not announced')

    expect(line?.classes()).toContain('text-muted-foreground')
  })

  it.each(['en', 'de'] as const)(
    'says nothing about the end when the venue stated one (%s)',
    (locale) => {
      i18n.global.locale.value = locale
      const shown = lines({
        ...base,
        startTime: '23:00:00',
        endDate: '2026-06-13',
        endTime: '06:00:00',
      })

      expect(shown.join(' ')).not.toMatch(/End not announced|Ende nicht bekannt gegeben/)
    },
  )

  it.each(['en', 'de'] as const)(
    'says nothing about the end of a run of whole days (%s)',
    (locale) => {
      i18n.global.locale.value = locale
      const shown = lines({ ...base, endDate: '2026-06-14' })

      expect(shown.join(' ')).not.toMatch(/End not announced|Ende nicht bekannt gegeben/)
    },
  )

  it('says nothing about the end of a day with no time at all', () => {
    expect(lines(base).join(' ')).not.toContain('End not announced')
  })

  describe('changes (#2725)', () => {
    beforeEach(() => {
      vi.useFakeTimers()
      vi.setSystemTime(new Date('2026-10-09T12:00:00+02:00'))
    })
    afterEach(() => vi.useRealTimers())

    function changeLines(event: EventDetail): string[] {
      return mount(EventWhen, { props: { event } })
        .findAll('li')
        .map((line) => line.text())
    }

    it('shows each time, date and status change in the order the BFF sent it, with how long ago it was seen', () => {
      const shown = changeLines({
        ...base,
        eventDate: '2026-10-17',
        changes: [
          {
            field: 'STATUS',
            from: 'SCHEDULED',
            to: 'CANCELLED',
            seenAt: '2026-10-09T03:10:00+02:00',
          },
          { field: 'START_TIME', from: '22:00', to: '23:00', seenAt: '2026-10-08T03:10:00+02:00' },
          {
            field: 'EVENT_DATE',
            from: '2026-10-16',
            to: '2026-10-17',
            seenAt: '2026-10-07T03:10:00+02:00',
          },
          {
            field: 'VENUE',
            from: 'Lido',
            to: 'Festsaal Kreuzberg',
            seenAt: '2026-10-01T03:10:00+02:00',
          },
        ],
      })

      expect(shown).toEqual([
        'Cancelled · today',
        'Start moved from 22:00 to 23:00 · yesterday',
        'Date moved from Fri 16 Oct to Sat 17 Oct · 2 days ago',
      ])
      expect(shown.join(' ')).not.toContain('Lido')
    })

    it('leaves the venue change to the venue block and shows no list when it is the only change', () => {
      const event: EventDetail = {
        ...base,
        changes: [
          {
            field: 'VENUE',
            from: 'Lido',
            to: 'Festsaal Kreuzberg',
            seenAt: '2026-10-01T03:10:00+02:00',
          },
        ],
      }

      expect(mount(EventWhen, { props: { event } }).find('ul').exists()).toBe(false)
    })

    it('words every status an event can move to', () => {
      const statuses = ['SCHEDULED', 'CANCELLED', 'POSTPONED', 'RELOCATED']
      const shown = changeLines({
        ...base,
        changes: statuses.map((to) => ({
          field: 'STATUS' as const,
          from: 'SCHEDULED',
          to,
          seenAt: '2026-10-09T03:10:00+02:00',
        })),
      })

      expect(shown.map((line) => line.split(' · ')[0])).toEqual([
        'On again',
        'Cancelled',
        'Postponed',
        'Relocated',
      ])
    })

    it('shows the end of a night over midnight as a time and a long run by its last day', () => {
      const shown = changeLines({
        ...base,
        changes: [
          { field: 'END_TIME', from: '05:00', to: '08:00', seenAt: '2026-10-09T03:10:00+02:00' },
          {
            field: 'END_DATE',
            from: '2026-10-12',
            to: '2026-10-19',
            seenAt: '2026-10-09T03:10:00+02:00',
          },
        ],
      })

      expect(shown).toEqual([
        'End moved from 05:00 to 08:00 · today',
        'Last day moved from Mon 12 Oct to Mon 19 Oct · today',
      ])
    })

    it('shows no list for an event that has not moved, and leaves out a status it has no words for', () => {
      expect(changeLines(base)).toEqual([])
      expect(
        changeLines({
          ...base,
          changes: [
            {
              field: 'STATUS',
              from: 'SCHEDULED',
              to: 'SOLD_OUT',
              seenAt: '2026-10-09T03:10:00+02:00',
            },
          ],
        }),
      ).toEqual([])
    })
  })
})
