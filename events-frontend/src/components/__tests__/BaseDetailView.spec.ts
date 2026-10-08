import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import { mount } from '@vue/test-utils'
import BaseDetailView from '@/components/BaseDetailView.vue'
import type { EventPage, EventSummary } from '@/api/types'
import type { PastEvents } from '@/composables/usePastEvents'

const stubs = {
  RouterLink: { template: '<a :href="to"><slot /></a>', props: ['to'] },
  EventCard: { template: '<article :data-slug="event.slug" />', props: ['event'] },
  EventRow: { template: '<article :data-slug="event.slug" />', props: ['event'] },
  CachedImage: true,
}

function page(...content: EventSummary[]): EventPage {
  return { content }
}

function pager(events: EventSummary[], more: Partial<PastEvents> = {}): PastEvents {
  return {
    events,
    count: events.length,
    hasMore: false,
    loadingMore: false,
    error: null,
    loadMore: vi.fn<() => Promise<void>>(async () => {}),
    ...more,
  }
}

function mountWith(events: EventPage, past: PastEvents) {
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
      past,
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

  it('keeps past events collapsed under a heading with their count', () => {
    const over: EventSummary = { slug: 'last-week', eventDate: '2026-09-27' }

    const wrapper = mountWith(page(), pager([over], { count: 53 }))

    const details = wrapper.get('details')
    expect(details.attributes('open')).toBeUndefined()
    expect(details.get('summary').text()).toBe('Past events · 53')
    expect(slugsIn(wrapper, 'details')).toEqual(['last-week'])
  })

  it('offers Show more while more past events exist, and loads them on click', async () => {
    const past = pager([{ slug: 'last-week' }], { hasMore: true })
    const wrapper = mountWith(page(), past)

    const button = wrapper.get('[data-testid="past-show-more"]')
    expect(button.text()).toBe('Show more')
    await button.trigger('click')

    expect(past.loadMore).toHaveBeenCalledOnce()
  })

  it('disables Show more while a page loads', () => {
    const wrapper = mountWith(page(), pager([{ slug: 'a' }], { hasMore: true, loadingMore: true }))

    expect(wrapper.get('[data-testid="past-show-more"]').attributes('disabled')).toBeDefined()
  })

  it('keeps Show more beside the error after a failed page', () => {
    const wrapper = mountWith(
      page(),
      pager([{ slug: 'a' }], { hasMore: true, error: "Couldn't load past events." }),
    )

    expect(wrapper.get('details').text()).toContain("Couldn't load past events.")
    expect(wrapper.find('[data-testid="past-show-more"]').exists()).toBe(true)
  })

  it('drops Show more after the last page', () => {
    const wrapper = mountWith(page(), pager([{ slug: 'a' }]))

    expect(wrapper.find('[data-testid="past-show-more"]').exists()).toBe(false)
  })

  // The page starts where the header does; the profile keeps the reading measure (#2828).
  it('takes the listings width and keeps the profile at the reading measure, left-aligned', () => {
    const wrapper = mountWith(page({ slug: 'wanda', eventDate: '2027-04-14' }), pager([]))

    expect(wrapper.get('main').classes()).toEqual(expect.arrayContaining(['mx-auto', 'max-w-5xl']))
    const profile = wrapper.get('[data-testid="detail-profile"]')
    expect(profile.classes()).toContain('max-w-176')
    expect(profile.classes()).not.toContain('mx-auto')
    expect(profile.find('h1').text()).toBe('Sesh Orka')
  })

  it('hides the past section when no past event is left to show', () => {
    const wrapper = mountWith(page({ slug: 'heidegluhen-35' }), pager([]))

    expect(wrapper.find('details').exists()).toBe(false)
  })

  // design.instructions.md §1 forbids an eyebrow above a page title (#2662).
  it('opens the header on the h1, with no kind label above it', () => {
    const wrapper = mountWith(page(), pager([]))

    const header = wrapper.find('header')
    expect(header.element.firstElementChild?.tagName).toBe('H1')
    expect(header.text()).not.toContain('Artist')
  })
})
