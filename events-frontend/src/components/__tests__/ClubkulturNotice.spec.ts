import { afterEach, describe, expect, it, vi } from 'vitest'

import { mount } from '@vue/test-utils'
import ClubkulturNotice from '@/components/ClubkulturNotice.vue'

/** The notice retires itself after the festival's last day, Berlin time (#2296). */
describe('ClubkulturNotice', () => {
  afterEach(() => {
    vi.useRealTimers()
  })

  function mountAt(instant: string) {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(instant))
    return mount(ClubkulturNotice)
  }

  it('links to the programme on the last day', () => {
    // 23:30 on 11 October in Berlin (CEST, UTC+2).
    const wrapper = mountAt('2026-10-11T21:30:00Z')

    const link = wrapper.get('a')
    expect(link.attributes('href')).toBe('https://tagderclubkultur.berlin/programm/')
    expect(link.attributes('rel')).toBe('noopener')
  })

  it('renders nothing once the last day has ended in Berlin', () => {
    // Still 11 October in UTC, already 12 October in Berlin.
    const wrapper = mountAt('2026-10-11T22:30:00Z')

    expect(wrapper.find('aside').exists()).toBe(false)
  })

  it('loads nothing from the festival site', () => {
    const wrapper = mountAt('2026-10-03T12:00:00Z')

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.html()).not.toContain('src=')
  })
})
