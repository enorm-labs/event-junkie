import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn<() => Promise<unknown>>() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: SearchView } = await import('@/views/SearchView.vue')
const { addDays, todayIso } = await import('@/lib/format')

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
})

const event = (slug: string) => ({ slug, title: slug, eventDate: '2026-01-10' })

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
  })
  await router.push(path)
  wrapper = mount(SearchView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('SearchView', () => {
  it("styles 'Show all' as a link, not as the section label (#2668)", async () => {
    getMock.mockResolvedValue({
      venues: { items: [], total: 0 },
      events: { items: [], total: 100, totalCapped: true },
      artists: { items: [], total: 0 },
      promoters: { items: [], total: 0 },
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
    })
    await router.push('/en/search?q=techno')
    wrapper = mount(SearchView, { global: { plugins: [router] } })
    await flushPromises()

    const showAll = wrapper.find('a[href="/en/events?q=techno"]')
    expect(showAll.exists()).toBe(true)
    expect(showAll.classes()).toEqual(
      expect.arrayContaining(['text-primary', 'underline-offset-4', 'hover:underline']),
    )
    expect(showAll.classes()).not.toContain('text-muted-foreground')
  })

  it('asks for past events and lists them last, with a link to the list up to yesterday (#2854)', async () => {
    getMock.mockResolvedValue({
      venues: { items: [], total: 0 },
      events: { items: [event('tonight')], total: 1 },
      artists: { items: [], total: 0 },
      promoters: { items: [{ slug: 'lido-booking', name: 'Lido Booking' }], total: 1 },
      past: { items: [event('last-month'), event('last-year')], total: 21 },
    })
    const view = await mountAt('/en/search?q=lido')

    expect(getMock).toHaveBeenCalledWith(
      '/api/search',
      expect.objectContaining({ params: { query: { q: 'lido', limit: 20, past: true } } }),
    )
    const sections = view.findAll('section')
    expect(sections.map((section) => section.find('h2, span').text())).toEqual([
      expect.stringContaining('Events'),
      expect.stringContaining('Promoters'),
      expect.stringContaining('Past events'),
    ])
    const past = sections[sections.length - 1]!
    expect(past.text()).toContain('21')
    expect(past.findAll('li')).toHaveLength(2)
    const yesterday = addDays(todayIso(), -1)
    expect(past.find(`a[href="/en/events?q=lido&to=${yesterday}"]`).exists()).toBe(true)
  })

  it('hides the past section when nothing in the past matches', async () => {
    getMock.mockResolvedValue({
      venues: { items: [], total: 0 },
      events: { items: [event('tonight')], total: 1 },
      artists: { items: [], total: 0 },
      promoters: { items: [], total: 0 },
      past: { items: [], total: 0 },
    })
    const view = await mountAt('/en/search?q=lido')

    expect(view.findAll('section')).toHaveLength(1)
    expect(view.text()).not.toContain('Past events')
  })
})
