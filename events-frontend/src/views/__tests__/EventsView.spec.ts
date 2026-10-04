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
  EventCard: { template: '<article :data-slug="event.slug" />', props: ['event'] },
  EventRow: { template: '<article :data-slug="event.slug" />', props: ['event'] },
  EventFilterBar: true,
}

let wrapper: VueWrapper | undefined

async function mountAt(path: string) {
  const router = createRouter({
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
