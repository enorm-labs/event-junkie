import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h, nextTick } from 'vue'

import { ApiError } from '@/api/client'
import type { EventDetail } from '@/api/types'

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn<(path: string, init?: unknown) => Promise<unknown>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: EventDetailView } = await import('@/views/EventDetailView.vue')

const Page = defineComponent({ render: () => h('p') })

const RETITLED: EventDetail = {
  slug: '2099-11-25-uber-arena-konstantinos-argiros',
  title: 'Konstantinos Argiros',
  eventDate: '2099-11-25',
}
const FORMER = '2099-11-25-uber-arena-wird'

function eventSlugs(): string[] {
  return getMock.mock.calls
    .filter(([path]) => path === '/api/events/{slug}')
    .map(([, init]) => (init as { params: { path: { slug: string } } }).params.path.slug)
}

let wrapper: VueWrapper | undefined
let router: Router

async function open(path: string) {
  router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/events/:slug', name: 'event', component: EventDetailView },
      { path: '/:locale(en|de)/:rest(.*)', component: Page },
    ],
  })
  await router.push('/en/venues')
  await router.push(path)
  wrapper = mount(EventDetailView, { global: { plugins: [router] } })
  await flushPromises()
  await nextTick()
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  getMock.mockImplementation((path: string, init?: unknown) => {
    if (path !== '/api/events/{slug}') return Promise.resolve([])
    const { slug } = (init as { params: { path: { slug: string } } }).params.path
    return [RETITLED.slug, FORMER].includes(slug)
      ? Promise.resolve(RETITLED)
      : Promise.reject(new ApiError(404, 'not found'))
  })
})

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  vi.clearAllMocks()
})

describe('the event page on a former slug', () => {
  it("replaces the route with the event's own slug, keeping the locale, query and hash", async () => {
    await open(`/de/events/${FORMER}?ref=share#lineup`)

    expect(router.currentRoute.value.fullPath).toBe(`/de/events/${RETITLED.slug}?ref=share#lineup`)
    expect(eventSlugs()).toEqual([FORMER, RETITLED.slug])
  })

  it('leaves no history entry behind, so Back skips the old URL', async () => {
    await open(`/en/events/${FORMER}`)

    router.back()
    await flushPromises()

    expect(router.currentRoute.value.path).toBe('/en/venues')
  })
})

describe('the event page on any other slug', () => {
  it('stays on the route and loads once when the slug is the event’s own', async () => {
    await open(`/en/events/${RETITLED.slug}`)

    expect(router.currentRoute.value.path).toBe(`/en/events/${RETITLED.slug}`)
    expect(eventSlugs()).toEqual([RETITLED.slug])
  })

  it('stays on the route with the not-found state when the slug is unknown', async () => {
    await open('/en/events/nope')

    expect(router.currentRoute.value.path).toBe('/en/events/nope')
    expect(eventSlugs()).toEqual(['nope'])
  })
})
