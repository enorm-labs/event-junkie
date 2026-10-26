import { describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import type { EventSummary } from '@/api/types'

vi.mock('@/api/account', () => ({
  fetchSession: vi.fn<() => Promise<null>>(() => Promise.resolve(null)),
  fetchRsvps: vi.fn<() => Promise<Record<string, string>>>(() => Promise.resolve({})),
}))

const { default: EventCard } = await import('@/components/EventCard.vue')
const { default: EventRow } = await import('@/components/EventRow.vue')
const { useRsvps } = await import('@/composables/useRsvps')

const event: EventSummary = { slug: 'tonight', title: 'Tonight', eventDate: '2026-10-30' }
const stubs = { RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] } }

describe('the RSVP mark on a card and a row (#3892)', () => {
  it.each([EventCard, EventRow])(
    'says going or interested, and nothing without an RSVP',
    (component) => {
      const { statuses } = useRsvps()
      statuses.value = {}
      const plain = mount(component, { props: { event }, global: { stubs } })
      expect(plain.find('[data-testid="rsvp-badge"]').exists()).toBe(false)

      statuses.value = { tonight: 'going' }
      const going = mount(component, { props: { event }, global: { stubs } })
      expect(going.find('[data-testid="rsvp-badge"]').text()).toBe('You are going')

      statuses.value = { tonight: 'interested' }
      const interested = mount(component, { props: { event }, global: { stubs } })
      expect(interested.find('[data-testid="rsvp-badge"]').text()).toBe('You are interested')
    },
  )
})
