import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'

import type { ManualVenue } from '@/api/venues'
import ManualVenuesView from '@/views/ManualVenuesView.vue'

const NOW = Date.parse('2026-10-19T12:00:00Z')
const DAY = 24 * 60 * 60 * 1000

function venue(overrides: Partial<ManualVenue>): ManualVenue {
  return {
    id: 1,
    slug: 'x',
    name: 'X',
    programmeUrl: 'https://x.example/programm',
    programmeCheckedAt: null,
    newestEventCreatedAt: null,
    ...overrides,
  }
}

let listed: ManualVenue[] = []
const fetchMock = vi.fn<typeof fetch>()

function json(body: unknown, status = 200) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { 'Content-Type': 'application/json' },
  })
}

async function routerAt(path: string): Promise<Router> {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/manual-venues', name: 'manual-venues', component: ManualVenuesView },
      { path: '/events/new', name: 'new-event', component: { template: '<p>new event</p>' } },
    ],
  })
  await router.push(path)
  await router.isReady()
  return router
}

async function mountView() {
  const router = await routerAt('/manual-venues')
  const wrapper = mount(ManualVenuesView, { global: { plugins: [router] } })
  await flushPromises()
  return { wrapper, router }
}

const names = (wrapper: Awaited<ReturnType<typeof mountView>>['wrapper']) =>
  wrapper.findAll('[data-testid="manual-row"]').map((r) => r.find('a').text())

beforeEach(() => {
  vi.useFakeTimers({ toFake: ['Date'] })
  vi.setSystemTime(NOW)
  fetchMock.mockImplementation(async (input, init) => {
    const path = String(input)
    if (path === '/api/admin/venues/manual') return json(listed)
    const checked = path.match(/^\/api\/admin\/venues\/(\d+)\/programme-checked$/)
    if (checked && init?.method === 'PUT') {
      return json({ id: Number(checked[1]), programmeCheckedAt: new Date(NOW).toISOString() })
    }
    return json({ detail: `unexpected ${path}` }, 500)
  })
  vi.stubGlobal('fetch', fetchMock)
})

afterEach(() => {
  vi.useRealTimers()
  vi.clearAllMocks()
  vi.unstubAllGlobals()
})

describe('ManualVenuesView', () => {
  it('lists the never-checked venue first, then the oldest check, then by name', async () => {
    listed = [
      venue({ id: 1, name: 'Recent', programmeCheckedAt: new Date(NOW - 2 * DAY).toISOString() }),
      venue({ id: 2, name: 'Old', programmeCheckedAt: new Date(NOW - 30 * DAY).toISOString() }),
      venue({ id: 3, name: 'Never B', programmeCheckedAt: null }),
      venue({ id: 4, name: 'Never A', programmeCheckedAt: null }),
    ]
    const { wrapper } = await mountView()

    expect(names(wrapper)).toEqual(['Never A', 'Never B', 'Old', 'Recent'])
  })

  it('marks a venue due 14 days after its check, and not a second earlier', async () => {
    listed = [
      venue({ id: 1, name: 'A Never', programmeCheckedAt: null }),
      venue({
        id: 2,
        name: 'B Exactly',
        programmeCheckedAt: new Date(NOW - 14 * DAY).toISOString(),
      }),
      venue({
        id: 3,
        name: 'C Almost',
        programmeCheckedAt: new Date(NOW - 14 * DAY + 1000).toISOString(),
      }),
    ]
    const { wrapper } = await mountView()

    const due = wrapper
      .findAll('[data-testid="manual-row"]')
      .map((r) => r.find('[data-testid="due"]').exists())
    expect(due).toEqual([true, true, false])
    expect(wrapper.find('[data-testid="overdue-count"]').text()).toBe('2 of 3 venues overdue.')
  })

  it('shows the programme link and the day the newest event was entered', async () => {
    listed = [venue({ newestEventCreatedAt: '2026-10-14T22:30:00Z' })]
    const { wrapper } = await mountView()

    const row = wrapper.find('[data-testid="manual-row"]')
    expect(row.find('[data-testid="programme-link"]').attributes('href')).toBe(
      'https://x.example/programm',
    )
    // 22:30 UTC is the next day in Berlin.
    expect(row.text()).toContain('2026-10-15')
  })

  it('Checked PUTs the check, and the row moves to the end without its Due badge', async () => {
    listed = [
      venue({ id: 5, name: 'Funkloch', programmeCheckedAt: null }),
      venue({ id: 6, name: 'Zebra', programmeCheckedAt: new Date(NOW - 20 * DAY).toISOString() }),
    ]
    const { wrapper } = await mountView()

    await wrapper.find('[data-testid="manual-row"] [data-testid="checked"]').trigger('click')
    await flushPromises()

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/admin/venues/5/programme-checked',
      expect.objectContaining({ method: 'PUT' }),
    )
    expect(names(wrapper)).toEqual(['Zebra', 'Funkloch'])
    const funkloch = wrapper.findAll('[data-testid="manual-row"]')[1]!
    expect(funkloch.find('[data-testid="due"]').exists()).toBe(false)
    expect(wrapper.find('[role="status"]').text()).toContain('Funkloch is checked')
  })

  it('shows the error and keeps the row when the PUT fails', async () => {
    listed = [venue({ id: 99, name: 'Gone' })]
    const { wrapper } = await mountView()
    fetchMock.mockImplementationOnce(async () =>
      json({ detail: 'Venue with id 99 not found' }, 404),
    )

    await wrapper.find('[data-testid="checked"]').trigger('click')
    await flushPromises()

    expect(wrapper.find('[role="alert"]').text()).toContain('404')
    expect(wrapper.find('[data-testid="due"]').exists()).toBe(true)
  })

  it('New event opens the form with the venue chosen', async () => {
    listed = [venue({ id: 42, name: 'Funkloch' })]
    const { wrapper, router } = await mountView()

    await wrapper.find('[data-testid="new-event"]').trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.name).toBe('new-event')
    expect(router.currentRoute.value.query).toEqual({ venue: '42' })
  })
})
