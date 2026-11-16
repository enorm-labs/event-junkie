import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import { i18n } from '@/i18n'

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn<(url: string, init?: { params?: { query?: object } }) => Promise<unknown>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: PromotersView } = await import('@/views/PromotersView.vue')

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  getMock.mockReset()
  i18n.global.locale.value = 'en'
})

async function mountAt(path: string, locale: 'en' | 'de' = 'en') {
  i18n.global.locale.value = locale
  // index.html ships the tag the page rewrites; jsdom starts with none.
  if (!document.querySelector('meta[name="description"]')) {
    document.head.insertAdjacentHTML('beforeend', '<meta name="description" content="">')
  }
  getMock.mockResolvedValue({
    content: [{ slug: 'x', name: 'X' }],
    totalElements: 1,
    totalPages: 1,
    number: 0,
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/:rest(.*)', component: defineComponent({ render: () => h('p') }) },
    ],
  })
  await router.push(path)
  wrapper = mount(PromotersView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

/** The `kind` the list request sent. */
function sentKind(): unknown {
  const query = getMock.mock.calls.find(([url]) => url === '/api/promoters')?.[1]?.params?.query
  return (query as { kind?: unknown } | undefined)?.kind
}

// #5091: one kind names the page; the filter sends only the kinds it offers.
describe('PromotersView kind filter', () => {
  it.each([
    ['en', 'collective', 'Collectives in Berlin'],
    ['en', 'party', 'Party series in Berlin'],
    ['de', 'collective', 'Kollektive in Berlin'],
    ['de', 'label', 'Labels in Berlin'],
  ] as const)('in %s, ?kind=%s is "%s"', async (locale, kind, heading) => {
    const view = await mountAt(`/${locale}/promoters?kind=${kind}`, locale)

    expect(sentKind()).toEqual([kind])
    expect(view.get('h1').text()).toBe(heading)
    expect(document.title).toContain(heading)
    expect(document.querySelector('meta[name="description"]')?.getAttribute('content')).toBe(
      i18n.global.t(`promoters.kindSubtitle.${kind}`),
    )
  })

  it('keeps "Promoters" for two kinds, and sends both', async () => {
    const view = await mountAt('/en/promoters?kind=collective&kind=label')

    expect(sentKind()).toEqual(['collective', 'label'])
    expect(view.get('h1').text()).toBe('Promoters')
  })

  it('drops a kind the filter does not offer, rather than asking for a 400', async () => {
    const view = await mountAt('/en/promoters?kind=media&kind=nope')

    expect(sentKind()).toBeUndefined()
    expect(view.get('h1').text()).toBe('Promoters')
  })

  it('names the one kind on the filter trigger', async () => {
    const view = await mountAt('/en/promoters?kind=label')

    expect(view.get('button[aria-label^="Filter by kind"]').attributes('aria-label')).toBe(
      'Filter by kind: Labels',
    )
  })
})
