import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { type Component, defineComponent, h } from 'vue'

import { yesterdayIso } from '@/lib/format'

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn<(path: string, init?: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: VenueDetailView } = await import('@/views/VenueDetailView.vue')
const { default: ArtistDetailView } = await import('@/views/ArtistDetailView.vue')
const { default: PromoterDetailView } = await import('@/views/PromoterDetailView.vue')

const Page = defineComponent({ render: () => h('p') })

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  getMock.mockReset()
})

// Each page passes its own filter to the shared past-events pager (#2860), and its own slug to
// the upcoming feed and the report line, through `useDetailPage` (#2570).
describe.each<[string, string, Component]>([
  ['venue', 'venues', VenueDetailView],
  ['artist', 'artists', ArtistDetailView],
  ['promoter', 'promoters', PromoterDetailView],
])('the %s page', (filter, segment, view) => {
  async function mountPage() {
    getMock.mockImplementation((path: string) =>
      Promise.resolve(path === '/api/events' ? { content: [] } : { slug: 'kater', name: 'Kater' }),
    )
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [
        { path: `/:locale(en|de)/${segment}/:slug`, component: view },
        { path: '/:locale(en|de)/:rest(.*)', component: Page },
      ],
    })
    await router.push(`/en/${segment}/kater`)
    wrapper = mount(view, { global: { plugins: [router] } })
    await flushPromises()
    return wrapper
  }

  it('asks for its own past events, newest first, 20 at a time', async () => {
    await mountPage()

    expect(getMock).toHaveBeenCalledWith('/api/events', {
      params: {
        query: { [filter]: 'kater', to: yesterdayIso(), size: 20, sort: ['eventDate,desc'] },
      },
    })
  })

  it('asks for its own upcoming events and names its own path in the report mail', async () => {
    const page = await mountPage()

    expect(getMock).toHaveBeenCalledWith('/api/events', {
      params: { query: { [filter]: 'kater', size: 50 } },
    })
    const mail = page.findAll('a').find((a) => a.attributes('href')?.startsWith('mailto:'))
    expect(decodeURIComponent(mail?.attributes('href') ?? '')).toContain(`/en/${segment}/kater`)
  })
})
