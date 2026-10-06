import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { type Component, defineComponent, h } from 'vue'

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn<(url: string, init?: { params?: { query?: object } }) => Promise<unknown>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: VenuesView } = await import('@/views/VenuesView.vue')
const { default: PromotersView } = await import('@/views/PromotersView.vue')

let wrapper: VueWrapper | undefined
let router: ReturnType<typeof createRouter>

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  getMock.mockReset()
})

async function mountAt(view: Component, path: string) {
  getMock.mockImplementation((url: string) =>
    Promise.resolve(
      url.endsWith('/feature-counts')
        ? {}
        : { content: [{ slug: 'x', name: 'X' }], totalElements: 1, totalPages: 1, number: 0 },
    ),
  )
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/:rest(.*)', component: defineComponent({ render: () => h('p') }) },
    ],
  })
  await router.push(path)
  wrapper = mount(view, { global: { plugins: [router] } })
  await flushPromises()
}

/** The `sort` of the list request, the one with paging. */
function listSort(endpoint: string): unknown {
  const call = getMock.mock.calls.find(
    ([url, init]) => url === endpoint && init?.params?.query && 'page' in init.params.query,
  )
  return (call?.[1]?.params?.query as { sort?: unknown } | undefined)?.sort
}

// #2694: without a sort the BFF lists a search by relevance, so the pressed A–Z is sent.
describe('the list pages send the order they show', () => {
  it.each([
    ['Venues', VenuesView, '/en/venues?q=berlin', '/api/venues'],
    ['Promoters', PromotersView, '/en/promoters?q=berlin', '/api/promoters'],
  ])('%s sends A–Z with a search', async (_, view, path, endpoint) => {
    await mountAt(view, path)
    expect(listSort(endpoint)).toEqual(['name,asc'])
  })

  it.each([
    ['Venues', VenuesView, '/en/venues', '/api/venues'],
    ['Promoters', PromotersView, '/en/promoters', '/api/promoters'],
  ])('%s flips A–Z to Z–A, and A–Z stays out of the URL', async (_, view, path, endpoint) => {
    await mountAt(view, path)
    const name = () => wrapper!.findAll('button').find((b) => /^[AZ]–[AZ]$/.test(b.text()))!
    expect(name().text()).toBe('A–Z')

    getMock.mockClear()
    await name().trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.sort).toBe('name,desc')
    expect(listSort(endpoint)).toEqual(['name,desc'])
    expect(name().text()).toBe('Z–A')

    await name().trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query.sort).toBeUndefined()
  })

  it('sends the 30-day order when it is chosen', async () => {
    await mountAt(PromotersView, '/en/promoters?sort=upcomingEvents,desc')
    expect(listSort('/api/promoters')).toEqual(['upcomingEvents,desc'])
    expect(wrapper!.text()).toContain('Busiest next 30 days')
  })
})

describe('the venue filters', () => {
  it('sit behind "More filters", which names how many are set', async () => {
    await mountAt(VenuesView, '/en/venues?district=mitte&type=club')
    const more = wrapper!.get('#more-filters')

    expect(more.text()).toContain('Mitte')
    expect(wrapper!.get('[aria-controls="more-filters"]').text()).toBe('More filters (2)')
  })
})
