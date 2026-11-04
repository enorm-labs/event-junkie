import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h, nextTick, ref } from 'vue'

import { ApiError } from '@/api/client'

const { getMock, fetchFollowNotifyMock } = vi.hoisted(() => ({
  getMock: vi.fn<(path: string, init?: unknown) => Promise<unknown>>(),
  fetchFollowNotifyMock: vi.fn<(kind: string, slug: string) => Promise<null>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

vi.mock('@/api/account', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/account')>()),
  fetchFollowNotify: fetchFollowNotifyMock,
}))

vi.mock('@/composables/useSession', () => ({
  useSession: () => ({ account: ref({ email: 'visitor@example.de' }) }),
}))

const { default: ArtistDetailView } = await import('@/views/ArtistDetailView.vue')

const Page = defineComponent({ render: () => h('p') })

const SURVIVOR = { slug: 'testbild', name: 'Testbild' }

function artistSlugs(): string[] {
  return getMock.mock.calls
    .filter(([path]) => path === '/api/artists/{slug}')
    .map(([, init]) => (init as { params: { path: { slug: string } } }).params.path.slug)
}

let wrapper: VueWrapper | undefined
let router: Router

async function open(path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/artists/:slug', name: 'artist', component: ArtistDetailView },
      { path: '/:locale(en|de)/:rest(.*)', component: Page },
    ],
  })
  await router.push('/en/events')
  await router.push(path)
  wrapper = mount(ArtistDetailView, { global: { plugins: [router] } })
  await flushPromises()
  await nextTick()
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  fetchFollowNotifyMock.mockResolvedValue(null)
  getMock.mockImplementation((path: string, init?: unknown) => {
    if (path === '/api/events') return Promise.resolve({ content: [] })
    const { slug } = (init as { params: { path: { slug: string } } }).params.path
    return ['testbild', 'dj-testbild'].includes(slug)
      ? Promise.resolve(SURVIVOR)
      : Promise.reject(new ApiError(404, 'not found'))
  })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.clearAllMocks()
})

describe('the artist page on an alias slug', () => {
  it("replaces the route with the artist's own slug, keeping the locale, query and hash", async () => {
    await open('/de/artists/dj-testbild?ref=share#events')

    expect(router.currentRoute.value.fullPath).toBe('/de/artists/testbild?ref=share#events')
    expect(artistSlugs()).toEqual(['dj-testbild', 'testbild'])
  })

  it('leaves no history entry behind, so Back skips the old URL', async () => {
    await open('/en/artists/dj-testbild')

    router.back()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/en/events')
  })

  it('asks for the follow state and reports the problem under the current slug only', async () => {
    const page = await open('/en/artists/dj-testbild')

    expect(fetchFollowNotifyMock.mock.calls.map(([, slug]) => slug)).toEqual(['testbild'])
    const mail = page.findAll('a').find((a) => a.attributes('href')?.startsWith('mailto:'))
    const body = decodeURIComponent(mail?.attributes('href') ?? '')
    expect(body).toContain('/en/artists/testbild')
    expect(body).not.toContain('dj-testbild')
  })

  it('asks for events by the current slug after the replace', async () => {
    await open('/en/artists/dj-testbild')

    const filters = getMock.mock.calls
      .filter(([path]) => path === '/api/events')
      .map(([, init]) => (init as { params: { query: { artist: string } } }).params.query.artist)
    expect(filters[filters.length - 1]).toBe('testbild')
  })
})

describe('the artist page on any other slug', () => {
  it('stays on the route and loads once when the slug is the artist’s own', async () => {
    await open('/en/artists/testbild')

    expect(router.currentRoute.value.path).toBe('/en/artists/testbild')
    expect(artistSlugs()).toEqual(['testbild'])
  })

  it('stays on the route with the not-found state when the slug is unknown', async () => {
    const page = await open('/en/artists/nope')

    expect(router.currentRoute.value.path).toBe('/en/artists/nope')
    expect(page.text()).toContain('Artist not found')
  })
})
