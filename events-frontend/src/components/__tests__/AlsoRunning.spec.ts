import { afterEach, describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import AlsoRunning from '@/components/AlsoRunning.vue'
import { useCompactView } from '@/composables/useCompactView'
import type { EventSummary } from '@/api/types'

const exhibition: EventSummary = {
  slug: 'exhibition',
  title: 'Exhibition',
  eventDate: '2026-10-03',
  endDate: '2026-10-18',
  venue: { slug: 'panorama-bar', name: 'Panorama Bar', city: 'Berlin' },
}

const stubs = {
  EventCard: {
    template: '<article data-card :data-as="as">{{ event.title }}</article>',
    props: ['event', 'as'],
  },
  EventRow: {
    template: '<article data-row :data-as="as">{{ event.title }}</article>',
    props: ['event', 'as'],
  },
}

describe('AlsoRunning', () => {
  const { compact } = useCompactView()
  afterEach(() => {
    compact.value = false
    window.history.replaceState(null, '')
  })

  it('is collapsed, and draws each run as the same card the list above draws', () => {
    const wrapper = mount(AlsoRunning, {
      props: { events: [exhibition], as: 'h2' },
      global: { stubs },
    })

    expect(wrapper.get('details').attributes('open')).toBeUndefined()
    expect(wrapper.get('summary').text()).toContain('Also running')
    expect(wrapper.get('summary').text()).toContain('(1)')
    const card = wrapper.get('[data-card]')
    expect(card.text()).toBe('Exhibition')
    expect(card.attributes('data-as')).toBe('h2')
    expect(wrapper.find('[data-row]').exists()).toBe(false)
  })

  it('draws rows in the compact view', () => {
    compact.value = true
    const wrapper = mount(AlsoRunning, { props: { events: [exhibition] }, global: { stubs } })

    expect(wrapper.get('[data-row]').attributes('data-as')).toBe('h3')
    expect(wrapper.find('[data-card]').exists()).toBe(false)
  })

  it('renders nothing without a folded run', () => {
    const wrapper = mount(AlsoRunning, { props: { events: [] }, global: { stubs } })

    expect(wrapper.find('details').exists()).toBe(false)
  })

  it('keeps its open state on the history entry, so Back reopens it', async () => {
    window.history.replaceState({ position: 3 }, '')
    const first = mount(AlsoRunning, { props: { events: [exhibition] }, global: { stubs } })
    const details = first.get('details').element as HTMLDetailsElement
    details.open = true
    await first.get('details').trigger('toggle')

    expect(window.history.state).toEqual({ position: 3, alsoRunningOpen: true })
    first.unmount()

    const returned = mount(AlsoRunning, { props: { events: [exhibition] }, global: { stubs } })
    expect(returned.get('details').attributes('open')).toBeDefined()
  })
})
