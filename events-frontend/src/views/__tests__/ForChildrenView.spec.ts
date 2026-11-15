import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import type { EventSummary } from '@/api/types'
import { setI18nLocale } from '@/i18n'

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn<(path: string, init: { params: { query: unknown } }) => Promise<unknown>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: ForChildrenView } = await import('@/views/ForChildrenView.vue')

const Page = defineComponent({ render: () => h('p') })

const stubs = {
  EventCard: { template: '<article :data-slug="event.slug" />', props: { event: Object } },
  EventRow: { template: '<article :data-slug="event.slug" />', props: { event: Object } },
}

let wrapper: VueWrapper | undefined

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
  })
  await router.push(path)
  setI18nLocale(path.startsWith('/de') ? 'de' : 'en')
  wrapper = mount(ForChildrenView, { global: { plugins: [router], stubs } })
  await flushPromises()
  return wrapper
}

function show(slug: string): EventSummary {
  return { slug, title: slug, eventDate: '2099-01-10', startTime: '10:30' }
}

function eventRequests() {
  return getMock.mock.calls.filter(([path]) => path === '/api/events')
}

describe('ForChildrenView', () => {
  beforeEach(() => {
    getMock.mockReset()
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    setI18nLocale('en')
  })

  it("asks for the children's events only, and lists them", async () => {
    getMock.mockResolvedValue({
      content: [show('pettersson'), show('grueffelo')],
      page: 0,
      totalPages: 1,
      totalElements: 2,
    })
    const view = await mountAt('/de/for-children')

    expect(eventRequests()).toHaveLength(1)
    expect(eventRequests()[0]![1].params.query).toEqual(
      expect.objectContaining({ feature: ['for-children'], page: 0, size: 20 }),
    )
    expect(view.findAll('article').map((card) => card.attributes('data-slug'))).toEqual([
      'pettersson',
      'grueffelo',
    ])
    expect(view.get('h1').text()).toBe('Für Kinder')
    expect(view.text()).toContain('2 Events')
    expect(view.text()).not.toContain('Weitere Events laden')
  })

  it('links the events list filtered to children, for the filters this page leaves out', async () => {
    getMock.mockResolvedValue({ content: [show('a')], page: 0, totalPages: 1, totalElements: 1 })
    const view = await mountAt('/en/for-children')

    const link = view.findAll('a').find((a) => a.text() === 'Filter by day, district or price')
    expect(link?.attributes('href')).toBe('/en/events?feature=for-children')
  })

  it('offers the next page while there is one', async () => {
    getMock.mockResolvedValue({ content: [show('a')], page: 0, totalPages: 3, totalElements: 41 })
    const view = await mountAt('/en/for-children')

    expect(view.get('a[href*="page=1"]').attributes('href')).toBe('/en/for-children?page=1')
  })

  it('says so when nothing is listed, and offers every event instead', async () => {
    getMock.mockResolvedValue({ content: [], page: 0, totalPages: 0, totalElements: 0 })
    const view = await mountAt('/en/for-children')

    expect(view.text()).toContain('No children’s events are on right now.')
    expect(view.get('a[href="/en/events"]').text()).toBe('All events')
    expect(view.find('article').exists()).toBe(false)
  })

  it('names the request that failed', async () => {
    getMock.mockRejectedValue(new Error('boom'))
    const view = await mountAt('/en/for-children')

    expect(view.text()).toContain("the children's events")
  })
})
