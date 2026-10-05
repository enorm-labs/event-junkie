import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { type Component, defineComponent, h } from 'vue'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn<(url: string) => Promise<unknown>>() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: VenuesView } = await import('@/views/VenuesView.vue')
const { default: PromotersView } = await import('@/views/PromotersView.vue')

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  getMock.mockReset()
})

function pageOf(content: object[]) {
  return { content, totalElements: content.length, totalPages: content.length ? 1 : 0, number: 0 }
}

/** Answers every list request with `content`, and the feature counts with nothing. */
async function mountAt(view: Component, path: string, content: object[]) {
  getMock.mockImplementation((url: string) =>
    Promise.resolve(url.endsWith('/feature-counts') ? {} : pageOf(content)),
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)', component: Page }],
  })
  await router.push(path)
  wrapper = mount(view, { global: { plugins: [router] } })
  await flushPromises()
}

/** The filter panel: the element that holds the search form. */
function panelButtons(): string[] {
  const panel = wrapper!.get('form[role="search"]').element.parentElement!
  return [...panel.querySelectorAll('button')].map((button) => button.textContent!.trim())
}

const VENUE = { slug: 'lido', name: 'Lido' }
const PROMOTER = { slug: 'goodlive', name: 'Goodlive' }

describe('"Clear all" beside the filters (#2692)', () => {
  it.each([
    ['Venues', VenuesView, '/en/venues?character=smoke-free&q=club', VENUE],
    ['Promoters', PromotersView, '/en/promoters?q=berlin', PROMOTER],
  ])('%s shows it when filtered', async (_, view, path, item) => {
    await mountAt(view, path, [item])
    expect(panelButtons()).toContain('Clear all')
  })

  it.each([
    ['Venues', VenuesView, '/en/venues?sort=upcomingEvents,desc', VENUE],
    ['Promoters', PromotersView, '/en/promoters?sort=upcomingEvents,desc', PROMOTER],
  ])('%s hides it when only the order is set', async (_, view, path, item) => {
    await mountAt(view, path, [item])
    expect(panelButtons()).not.toContain('Clear all')
  })
})

describe('the venues empty state', () => {
  it('names the filters as the cause, and offers to clear them', async () => {
    await mountAt(VenuesView, '/en/venues?district=mitte&q=club', [])
    expect(wrapper!.text()).toContain('No venues match these filters.')
    expect(wrapper!.findAll('button').map((button) => button.text())).toContain('Clear all filters')
  })

  it('keeps the name-search sentence for a search alone', async () => {
    await mountAt(VenuesView, '/en/venues?q=nothing', [])
    expect(wrapper!.text()).toContain('No venues match that search. Try a different name.')
  })
})
