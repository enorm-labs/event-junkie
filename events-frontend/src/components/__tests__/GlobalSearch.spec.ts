import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn<(path: string, init: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: GlobalSearch } = await import('@/components/GlobalSearch.vue')

const results = {
  events: {
    items: [
      {
        slug: 'klubnacht',
        title: 'Klubnacht',
        eventDate: '2026-10-10',
        venue: { name: 'Berghain' },
      },
    ],
    total: 40,
  },
  venues: { items: [{ slug: 'berghain', name: 'Berghain', district: 'friedrichshain' }], total: 1 },
  artists: { items: [], total: 0 },
  promoters: {
    items: [{ slug: 'ostgut', name: 'Ostgut Booking', upcomingEventCount: 3 }],
    total: 1,
  },
}

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined
let router: Router

async function mountAt(path = '/en') {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      {
        path: '/:locale(en|de)',
        children: [
          { path: '', component: Page },
          { path: ':rest(.*)', component: Page },
        ],
      },
    ],
  })
  await router.push(path)
  wrapper = mount(GlobalSearch, { global: { plugins: [router] }, attachTo: document.body })
}

async function search(term: string) {
  await wrapper!.get('button').trigger('click')
  await flushPromises()
  const input = wrapper!.get('input')
  await input.setValue(term)
  await new Promise((resolve) => setTimeout(resolve, 300))
  await flushPromises()
  return input
}

describe('GlobalSearch', () => {
  beforeEach(() => {
    // jsdom lays nothing out, and the listbox scrolls a highlighted option into view.
    Element.prototype.scrollIntoView = vi.fn<() => void>()
    getMock.mockReset()
    getMock.mockResolvedValue(results)
  })
  afterEach(() => {
    wrapper?.unmount()
  })

  it('opens on "/" outside a field, with the caret in the search field', async () => {
    await mountAt()
    window.dispatchEvent(new KeyboardEvent('keydown', { key: '/' }))
    await flushPromises()

    expect(document.activeElement?.getAttribute('aria-label')).toBe(
      'Search events, venues, artists and promoters',
    )
  })

  it('groups the results by kind, venues first, and leaves out an empty kind', async () => {
    await mountAt()
    await search('berghain')

    const labels = wrapper!
      .findAll('[role="group"]')
      .map((group) =>
        document.getElementById(group.attributes('aria-labelledby') ?? '')?.textContent?.trim(),
      )
      // The last group, the link to the results page, has no label.
      .filter(Boolean)
    expect(labels).toEqual(['Venues', 'Events', 'Promoters'])
    const options = wrapper!.findAll('[role="option"]').map((option) => option.text())
    expect(options[0]).toContain('Friedrichshain')
    expect(options[1]).toContain('Berghain')
    expect(options[options.length - 1]).toBe('All results for “berghain”')
    expect(getMock).toHaveBeenCalledWith(
      '/api/search',
      expect.objectContaining({ params: { query: { q: 'berghain', limit: 5 } } }),
    )
  })

  it('opens the detail page of the option picked', async () => {
    await mountAt()
    await search('berghain')
    await wrapper!.findAll('[role="option"]')[1]!.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/en/events/klubnacht')
  })

  it('opens the results page on Enter when no option is highlighted', async () => {
    await mountAt('/de')
    const input = await search('dj koze')
    await input.trigger('keydown', { key: 'Enter' })
    await flushPromises()

    expect(router.currentRoute.value.fullPath).toBe('/de/search?q=dj%20koze')
  })

  it('asks for a second character before it searches', async () => {
    await mountAt()
    await search('b')

    expect(getMock).not.toHaveBeenCalled()
    expect(wrapper!.text()).toContain('Type at least two characters.')
  })
})
