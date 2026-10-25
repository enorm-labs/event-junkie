import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import LocateStatus from '@/components/LocateStatus.vue'

describe('LocateStatus', () => {
  it('says nothing while idle', () => {
    const wrapper = mount(LocateStatus, { props: { state: 'idle', messages: 'map.near' } })

    expect(wrapper.find('p').exists()).toBe(false)
  })

  it('announces the search for a location the same way on both pages', () => {
    for (const messages of ['map.near', 'venues.near'] as const) {
      const wrapper = mount(LocateStatus, { props: { state: 'locating', messages } })

      expect(wrapper.get('p[aria-live="polite"]').text()).toBe('Finding your location…')
    }
  })

  it("offers each page's own way round a failed search", () => {
    const map = mount(LocateStatus, { props: { state: 'denied', messages: 'map.near' } })
    const venues = mount(LocateStatus, { props: { state: 'unavailable', messages: 'venues.near' } })

    expect(map.get('p[aria-live="polite"]').text()).toContain('Pick a point on the map')
    expect(venues.get('p[aria-live="polite"]').text()).toBe(
      'Your location could not be found. Search by name or district instead.',
    )
  })
})
