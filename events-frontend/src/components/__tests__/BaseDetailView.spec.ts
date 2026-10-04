import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { mount } from '@vue/test-utils'
import BaseDetailView from '@/components/BaseDetailView.vue'
import type { EventPage, EventSummary } from '@/api/types'
import { todayIso, yesterdayIso } from '@/lib/format'

const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
  EventCard: { template: '<article :data-slug="event.slug" />', props: ['event'] },
  EventRow: { template: '<article :data-slug="event.slug" />', props: ['event'] },
  CachedImage: true,
}

function page(...content: EventSummary[]): EventPage {
  return { content }
}

function mountWith(events: EventPage, pastEvents: EventPage) {
  return mount(BaseDetailView, {
    props: {
      kind: 'Artist',
      loading: false,
      error: null,
      notFound: false,
      ready: true,
      notFoundText: '',
      name: 'Sesh Orka',
      events,
      eventsLoading: false,
      eventsError: null,
      emptyText: '',
      pastEvents,
    },
    global: { stubs },
  })
}

function slugsIn(wrapper: ReturnType<typeof mountWith>, selector: string): string[] {
  return wrapper.findAll(`${selector} article`).map((card) => card.attributes('data-slug') ?? '')
}

describe('BaseDetailView', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-10-04T08:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  // The past query bounds the start date and the upcoming one the end date (#2560).
  it('lists an event that started yesterday and ends today only under upcoming', () => {
    const running: EventSummary = {
      slug: 'heidegluhen-35',
      eventDate: yesterdayIso(),
      endDate: todayIso(),
    }
    const over: EventSummary = { slug: 'last-week', eventDate: '2026-09-27' }

    const wrapper = mountWith(page(running), page(running, over))

    expect(slugsIn(wrapper, 'section')).toEqual(['heidegluhen-35'])
    expect(slugsIn(wrapper, 'details')).toEqual(['last-week'])
  })

  it('hides the past section when every past event is still upcoming', () => {
    const running: EventSummary = {
      slug: 'heidegluhen-35',
      eventDate: yesterdayIso(),
      endDate: todayIso(),
    }

    const wrapper = mountWith(page(running), page(running))

    expect(wrapper.find('details').exists()).toBe(false)
  })

  // design.instructions.md §1 forbids an eyebrow above a page title (#2662).
  it('opens the header on the h1, with no kind label above it', () => {
    const wrapper = mountWith(page(), page())

    const header = wrapper.find('header')
    expect(header.element.firstElementChild?.tagName).toBe('H1')
    expect(header.text()).not.toContain('Artist')
  })
})
