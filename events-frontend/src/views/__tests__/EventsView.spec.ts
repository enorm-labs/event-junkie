import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import type { EventSummary } from '@/api/types'

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn<(path: string, init: { params: { query: unknown } }) => Promise<unknown>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: EventsView } = await import('@/views/EventsView.vue')

const Page = defineComponent({ render: () => h('p') })

// 20:00 in Berlin on a Wednesday.
const NOW = '2026-10-07T18:00:00Z'
const TODAY = '2026-10-07'

const started: EventSummary = { slug: 'started', eventDate: TODAY, startTime: '19:00' }
const later: EventSummary = { slug: 'later', eventDate: TODAY, startTime: '23:00' }
const lastNight: EventSummary = {
  slug: 'weekender',
  eventDate: '2026-10-06',
  endDate: '2026-10-08',
  startTime: '22:00',
}

const stubs = {
  EventCard: {
    template: '<article :data-slug="event.slug" :data-lead="lead || undefined" />',
    props: { event: Object, lead: Boolean },
  },
  EventRow: { template: '<article :data-slug="event.slug" />', props: ['event'] },
  EventFilterBar: true,
}

let wrapper: VueWrapper | undefined
let router: ReturnType<typeof createRouter>

async function mountAt(path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
  })
  await router.push(path)
  wrapper = mount(EventsView, { global: { plugins: [router], stubs } })
  await flushPromises()
}

function slugs(): string[] {
  return wrapper!.findAll('article').map((card) => card.attributes('data-slug') ?? '')
}

describe('EventsView with On now', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(NOW))
    getMock.mockReset()
    getMock.mockImplementation((path) =>
      Promise.resolve(
        path === '/api/events/calendar'
          ? [lastNight, started, later]
          : { content: [later], page: 0, totalPages: 3, totalElements: 41 },
      ),
    )
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.useRealTimers()
  })

  it("reads today's calendar with the filters, and lists only what isOnNow accepts", async () => {
    await mountAt('/en/events?now=1&venue=lido&district=kreuzberg&page=2')

    expect(getMock).toHaveBeenCalledTimes(1)
    expect(getMock).toHaveBeenCalledWith('/api/events/calendar', {
      params: {
        query: expect.objectContaining({
          from: TODAY,
          to: TODAY,
          venue: 'lido',
          district: ['kreuzberg'],
        }),
      },
    })
    expect(slugs()).toEqual(['weekender', 'started'])
    expect(wrapper!.text()).toContain('2 events')
  })

  it('folds a run of weeks into Also running between its opening and its last days', async () => {
    const exhibition: EventSummary = {
      slug: 'exhibition',
      title: 'Exhibition',
      eventDate: '2026-10-03',
      endDate: '2026-10-18',
    }
    getMock.mockImplementation(() => Promise.resolve([exhibition, lastNight, started]))

    await mountAt('/en/events?now=1')

    const folded = wrapper!.get('details').findAll('article')
    expect(folded.map((card) => card.attributes('data-slug'))).toEqual(['exhibition'])
    expect(slugs()).toEqual(['weekender', 'started', 'exhibition'])
    expect(wrapper!.text()).toContain('3 events')
  })

  it('shows one list without pagination', async () => {
    await mountAt('/en/events?now=1')

    expect(wrapper!.text()).not.toMatch(/Page \d+ of/)
  })

  it('reads the paged search without On now', async () => {
    await mountAt('/en/events?venue=lido')

    expect(getMock).toHaveBeenCalledWith('/api/events', expect.anything())
    expect(getMock).not.toHaveBeenCalledWith('/api/events/calendar', expect.anything())
    expect(slugs()).toEqual(['later'])
    expect(wrapper!.text()).toMatch(/Page 1 of 3/)
  })
})

describe('EventsView calendar subscription', () => {
  beforeEach(() => {
    getMock.mockReset()
    getMock.mockResolvedValue({ content: [later], page: 0, totalPages: 1, totalElements: 1 })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
  })

  it('offers the calendar for the filters shown in one menu, named after them', async () => {
    await mountAt('/en/events?genre=jazz&district=neukoelln&from=2026-10-10')

    const menu = wrapper!.getComponent({ name: 'CalendarSubscribeMenu' })
    expect(menu.props('path')).toBe(
      '/calendar.ics?locale=en&district=neukoelln&genre=jazz&name=jazz+%C2%B7+Neuk%C3%B6lln',
    )
    expect(menu.props('name')).toBe('Event Junkie · jazz · Neukölln')
    const trigger = wrapper!.get('[data-testid="calendar-subscribe"]')
    expect(trigger.element.tagName).toBe('BUTTON')
    expect(trigger.text()).toContain('Subscribe to calendar')
    expect(trigger.attributes('title')).toMatch(/matching these filters/)
  })

  it('names an unfiltered calendar after the site', async () => {
    await mountAt('/en/events')

    expect(wrapper!.getComponent({ name: 'CalendarSubscribeMenu' }).props('name')).toBe(
      'Event Junkie',
    )
  })
})

describe('EventsView with a date range', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(NOW))
    getMock.mockReset()
    getMock.mockResolvedValue({ content: [], page: 0, totalPages: 0, totalElements: 0 })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.useRealTimers()
  })

  function query(): Record<string, unknown> {
    return getMock.mock.lastCall![1].params.query as Record<string, unknown>
  }

  it('asks for events still running on the first day of the range', async () => {
    await mountAt('/en/events?from=2026-10-10&to=2026-10-10')

    expect(query()).toMatchObject({ from: '2026-10-10', to: '2026-10-10', running: true })
  })

  it('sends no running flag without a from', async () => {
    await mountAt('/en/events?venue=lido')
    expect(query().running).toBeUndefined()

    wrapper!.unmount()
    await mountAt('/en/events?to=2026-10-10')
    expect(query().running).toBeUndefined()
  })

  it('folds a run of weeks that opened before the range into Also running', async () => {
    const exhibition: EventSummary = {
      slug: 'exhibition',
      title: 'Exhibition',
      eventDate: '2026-10-03',
      endDate: '2026-10-25',
    }
    const weekender: EventSummary = {
      slug: 'weekender',
      eventDate: '2026-10-09',
      endDate: '2026-10-12',
    }
    getMock.mockResolvedValue({
      content: [exhibition, weekender],
      page: 0,
      totalPages: 1,
      totalElements: 2,
    })

    await mountAt('/en/events?from=2026-10-10&to=2026-10-11')

    const folded = wrapper!.get('details').findAll('article')
    expect(folded.map((card) => card.attributes('data-slug'))).toEqual(['exhibition'])
    expect(slugs()).toEqual(['weekender', 'exhibition'])
  })
})

describe('EventsView lead', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(NOW))
    getMock.mockReset()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.useRealTimers()
  })

  it('puts the pick first at lead size, and does not show it twice', async () => {
    getMock.mockResolvedValue({
      content: [started, later],
      page: 0,
      totalPages: 1,
      totalElements: 2,
      lead: later,
    })

    await mountAt('/en/events')

    expect(slugs()).toEqual(['later', 'started'])
    expect(wrapper!.get('[data-lead]').attributes('data-slug')).toBe('later')
  })

  it('leads with a pick from a later page as well', async () => {
    const pick: EventSummary = { slug: 'pick', eventDate: '2026-10-20' }
    getMock.mockResolvedValue({
      content: [started, later],
      page: 0,
      totalPages: 3,
      totalElements: 41,
      lead: pick,
    })

    await mountAt('/en/events')

    expect(slugs()).toEqual(['pick', 'started', 'later'])
  })

  it('keeps the grid even without a pick', async () => {
    getMock.mockResolvedValue({
      content: [started, later],
      page: 0,
      totalPages: 1,
      totalElements: 2,
    })

    await mountAt('/en/events')

    expect(slugs()).toEqual(['started', 'later'])
    expect(wrapper!.find('[data-lead]').exists()).toBe(false)
  })
})

describe('EventsView sort', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date(NOW))
    getMock.mockReset()
    getMock.mockResolvedValue({ content: [later], page: 0, totalPages: 1, totalElements: 1 })
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    vi.useRealTimers()
  })

  function sortButtons(): string[] {
    return wrapper!
      .get('[role="group"]')
      .findAll('button')
      .map((button) => button.text())
  }

  function sentSort(): unknown {
    return (getMock.mock.lastCall![1].params.query as { sort?: unknown }).sort
  }

  function dateButton() {
    return wrapper!.get('[role="group"]').findAll('button')[0]!
  }

  it('offers newest added beside the date on the upcoming list, and sends it', async () => {
    await mountAt('/en/events?sort=createdAt,desc')

    expect(sortButtons()).toEqual(['Date', 'Newest added'])
    expect(sentSort()).toEqual(['createdAt,desc'])
  })

  it('drops newest added for a range into the past, where ended events show', async () => {
    await mountAt('/en/events?from=2026-09-01&sort=createdAt,desc')

    expect(sortButtons()).toEqual(['Date'])
    expect(dateButton().attributes('aria-label')).toBe('Date, earliest first')
    expect(sentSort()).toBeUndefined()
  })

  it('flips the date on a range into the past, and keeps the default out of the URL', async () => {
    await mountAt('/en/events?from=2026-09-01')

    await dateButton().trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.sort).toBe('eventDate,desc')
    expect(sentSort()).toEqual(['eventDate,desc'])
    expect(dateButton().attributes('aria-label')).toBe('Date, latest first')

    await dateButton().trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.sort).toBeUndefined()
  })

  it('reads a range wholly in the past latest first, and earliest first goes into the URL', async () => {
    await mountAt('/en/events?from=2026-09-01&to=2026-09-30')

    expect(dateButton().attributes('aria-label')).toBe('Date, latest first')
    await dateButton().trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.sort).toBe('eventDate,asc')
  })

  it('offers no sort on On now', async () => {
    getMock.mockResolvedValue([later])
    await mountAt('/en/events?now=1')

    expect(wrapper!.find('[role="group"]').exists()).toBe(false)
  })
})
