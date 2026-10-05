import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import type { EventSummary, VenueSummary } from '@/api/types'
import { todayIso } from '@/lib/format'
import type { MapPin } from '@/lib/mapPins'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn<(path: string) => Promise<unknown>>() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

// MapLibre needs WebGL; the stub records what the view asks of the map. `__esModule` makes the
// async loader take `default` rather than the module itself.
vi.mock('@/components/VenueMap.vue', () => ({
  default: defineComponent({
    name: 'VenueMap',
    props: {
      focus: { type: Object, default: null },
      selected: { type: String, default: null },
      pins: { type: Array, default: () => [] },
    },
    setup:
      (_, { slots }) =>
      () =>
        h('div', { 'data-testid': 'venue-map' }, slots.default?.()),
  }),
  __esModule: true,
}))

const { default: MapView } = await import('@/views/MapView.vue')

const Page = defineComponent({ render: () => h('p') })

const kater: VenueSummary = {
  slug: 'kater',
  name: 'Kater Blau',
  latitude: 52.5118,
  longitude: 13.4253,
}
const berghain: VenueSummary = {
  slug: 'berghain',
  name: 'Berghain',
  latitude: 52.5111,
  longitude: 13.4433,
}

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  getMock.mockReset()
})

async function mountMap(url: string, events: EventSummary[], venues = [kater, berghain]) {
  getMock.mockImplementation((path: string) => {
    if (path === '/api/venues') return Promise.resolve({ content: venues, totalPages: 1 })
    if (path === '/api/venues/{slug}') return Promise.resolve(kater)
    return Promise.resolve(events)
  })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/map', component: MapView },
      { path: '/:locale(en|de)/:rest(.*)', component: Page },
    ],
  })
  await router.push(url)
  wrapper = mount(MapView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper.getComponent({ name: 'VenueMap' })
}

function night(slug: string, venue: VenueSummary): EventSummary {
  return { slug, title: slug, eventDate: todayIso(), venue }
}

describe('MapView', () => {
  it('centres on the focused venue and opens its pin', async () => {
    const map = await mountMap('/en/map?focus=kater', [
      night('a', berghain),
      night('b', berghain),
      night('c', kater),
    ])

    expect(map.props('focus')).toEqual({ latitude: 52.5118, longitude: 13.4253 })
    expect(map.props('selected')).toBe('kater')
    expect(wrapper?.text()).toContain('Kater Blau')
    const pin = (map.props('pins') as MapPin[]).find((candidate) => candidate.slug === 'kater')
    expect(pin?.quiet).toBeUndefined()
    expect(wrapper?.text()).not.toContain('No events in this range')
  })

  it('gives a focused venue with nothing tonight a quiet pin and opens it', async () => {
    const map = await mountMap('/en/map?focus=kater', [night('a', berghain)])

    expect(map.props('focus')).toEqual({ latitude: 52.5118, longitude: 13.4253 })
    expect(map.props('selected')).toBe('kater')
    const pin = (map.props('pins') as MapPin[]).find((candidate) => candidate.slug === 'kater')
    expect(pin).toMatchObject({ quiet: true, label: 'Kater Blau: no events in this range' })
    expect(pin?.live).toBeUndefined()
    expect(wrapper?.text()).toContain('No events in this range')
    const hrefs = wrapper?.findAll('a').map((link) => link.attributes('href')) ?? []
    expect(hrefs).toContain('/en/venues/kater')
    // The count above the map counts venues with events, not the quiet one.
    expect(wrapper?.text()).toContain('1 event at 1 venue')
  })

  it('asks the venue endpoint once for a focused venue the venue list lacks', async () => {
    const map = await mountMap('/en/map?focus=kater', [night('a', berghain)], [berghain])

    expect(getMock.mock.calls.filter(([path]) => path === '/api/venues/{slug}')).toHaveLength(1)
    expect(map.props('focus')).toEqual({ latitude: 52.5118, longitude: 13.4253 })
    expect(map.props('selected')).toBe('kater')
  })

  it('draws no quiet pin for a focused venue without a coordinate', async () => {
    const nowhere: VenueSummary = { slug: 'kater', name: 'Kater Blau' }
    getMock.mockImplementation((path: string) => {
      if (path === '/api/venues') return Promise.resolve({ content: [berghain], totalPages: 1 })
      if (path === '/api/venues/{slug}') return Promise.resolve(nowhere)
      return Promise.resolve([night('a', berghain)])
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:locale(en|de)/map', component: MapView }],
    })
    await router.push('/en/map?focus=kater')
    wrapper = mount(MapView, { global: { plugins: [router] } })
    await flushPromises()
    const map = wrapper.getComponent({ name: 'VenueMap' })

    expect(map.props('focus')).toBeNull()
    expect(map.props('selected')).toBeNull()
    expect((map.props('pins') as MapPin[]).map((pin) => pin.slug)).toEqual(['berghain'])
  })

  it('frames every pin without a focus', async () => {
    const map = await mountMap('/en/map', [night('a', berghain)])

    expect(map.props('focus')).toBeNull()
    expect(map.props('selected')).toBeNull()
  })

  it('keeps focus out of the link to the list', async () => {
    const unpinned: VenueSummary = { slug: 'somewhere', name: 'Somewhere' }
    await mountMap('/en/map?focus=kater&family=techno', [
      night('a', berghain),
      night('b', unpinned),
    ])

    const hrefs = wrapper?.findAll('a').map((link) => link.attributes('href')) ?? []
    expect(hrefs).toContain('/en/events?family=techno')
    expect(hrefs.some((href) => href?.includes('focus='))).toBe(false)
  })
})
