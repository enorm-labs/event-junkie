import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import { useLocation } from '@/composables/useLocation'

// The real API client, unlike ChristmasMarketsView.spec.ts: what matters is what reaches `fetch` (#5010).
const LATITUDE = '52.5219'
const LONGITUDE = '13.4132'

const MARKETS = [
  { slug: 'a', title: 'Lucia', eventDate: '2026-11-23', endDate: '2026-12-31' },
  { slug: 'b', title: 'Rotes Rathaus', eventDate: '2026-11-23', endDate: '2026-12-31' },
].map((market, i) => ({
  ...market,
  venue: { slug: `v${i}`, name: market.title, latitude: 52.5 + i / 100, longitude: 13.4 },
}))

// Node's Request takes no relative URL, which the client's empty base URL gives; a browser resolves it.
class PageRequest extends Request {
  constructor(input: RequestInfo | URL, init?: RequestInit) {
    super(typeof input === 'string' ? new URL(input, window.location.origin) : input, init)
  }
}
// Before the client loads: openapi-fetch takes `Request` when the client is created.
vi.stubGlobal('Request', PageRequest)
const { default: ChristmasMarketsView } = await import('@/views/ChristmasMarketsView.vue')

const stubs = {
  VenueMap: defineComponent({ setup: () => () => h('div') }),
}

afterEach(() => {
  vi.restoreAllMocks()
  Reflect.deleteProperty(navigator, 'geolocation')
  useLocation().clear()
})

describe('ChristmasMarketsView nearest first', () => {
  it('sends no coordinate in any request or in the URL', async () => {
    const fetch = vi.spyOn(globalThis, 'fetch').mockImplementation(
      async () =>
        new Response(JSON.stringify({ content: MARKETS, totalElements: MARKETS.length }), {
          headers: { 'Content-Type': 'application/json' },
        }),
    )
    Object.defineProperty(navigator, 'geolocation', {
      configurable: true,
      value: {
        getCurrentPosition: (success: PositionCallback) =>
          success({
            coords: { latitude: Number(LATITUDE), longitude: Number(LONGITUDE), accuracy: 30 },
          } as GeolocationPosition),
      },
    })
    const router = createRouter({
      history: createMemoryHistory(),
      routes: [{ path: '/:locale(en|de)/:rest(.*)*', component: { render: () => h('p') } }],
    })
    await router.push('/en/christmas-markets')
    const wrapper = mount(ChristmasMarketsView, { global: { plugins: [router], stubs } })
    await flushPromises()

    const nearest = wrapper.findAll('button').find((b) => b.text() === 'Nearest first')
    await nearest?.trigger('click')
    await flushPromises()
    await wrapper
      .findAll('button')
      .find((b) => b.text() === 'Use my location')
      ?.trigger('click')
    await flushPromises()
    expect(useLocation().origin.value?.source).toBe('device')
    await wrapper.find('select').setValue('v1')
    await wrapper.find('select').trigger('change')
    await flushPromises()

    expect(useLocation().origin.value?.source).toBe('venue')
    expect(wrapper.text()).toContain('2 Christmas markets, nearest first')
    const urls = fetch.mock.calls.map(([input]) =>
      input instanceof Request ? input.url : String(input),
    )
    expect(urls.some((url) => url.includes('/api/events?'))).toBe(true)
    for (const url of urls) {
      expect(url).not.toContain(LATITUDE)
      expect(url).not.toContain(LONGITUDE)
      expect(url).not.toContain('52.51')
    }
    expect(router.currentRoute.value.fullPath).toBe('/en/christmas-markets?sort=distance')
    wrapper.unmount()
  })
})
