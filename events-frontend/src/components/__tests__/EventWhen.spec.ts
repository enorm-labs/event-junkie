import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import EventWhen from '@/components/EventWhen.vue'
import type { EventDetail } from '@/api/types'

/** The event page's When block: one line per time the event has, and none for a time it lacks (#2565). */

const base: EventDetail = { slug: 'a-night', title: 'A Night', eventDate: '2026-06-12' }

function lines(event: EventDetail): string[] {
  return mount(EventWhen, { props: { event } })
    .findAll('p')
    .map((line) => line.text())
}

describe('EventWhen', () => {
  it('shows the doors and no start when the venue published only the doors', () => {
    const shown = lines({ ...base, doorsTime: '19:00:00' })

    expect(shown).toHaveLength(2)
    expect(shown[1]).toBe('Doors 19:00')
    expect(shown.join(' ')).not.toContain('Start')
  })

  it('shows the start and no doors when the venue published only the start', () => {
    const shown = lines({ ...base, startTime: '21:00:00' })

    expect(shown).toHaveLength(2)
    expect(shown[1]).toBe('Start 21:00')
    expect(shown.join(' ')).not.toContain('Doors')
  })

  it('marks a start we estimated as our estimate', () => {
    const shown = lines({ ...base, assumedStartTime: '23:00:00' })

    expect(shown).toHaveLength(2)
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
})
