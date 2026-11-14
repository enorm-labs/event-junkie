import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter, type Router } from 'vue-router'
import { defineComponent, h } from 'vue'
import type { EventPage, EventSummary } from '@/api/types'
import type { EventSearchParams } from '@/composables/useEvents'
import { setI18nLocale } from '@/i18n'

const searchEvents = vi.fn<(params: EventSearchParams) => Promise<EventPage>>()

vi.mock('@/composables/useEvents', () => ({
  searchEvents: (params: EventSearchParams) => searchEvents(params),
}))

const { default: ChristmasMarketsView } = await import('@/views/ChristmasMarketsView.vue')

function market(
  title: string,
  slug: string,
  { free = false, district = 'mitte' }: { free?: boolean; district?: string } = {},
): EventSummary {
  return {
    slug,
    title,
    free,
    eventDate: '2026-11-23',
    endDate: '2026-12-31',
    venue: { slug: `${slug}-venue`, name: title, district, latitude: 52.51, longitude: 13.39 },
  } as EventSummary
}

const SEASON = [
  market('Altstadt Spandau', 'spandau', { district: 'spandau', free: true }),
  market('Gendarmenmarkt', 'gendarmen', { district: 'mitte' }),
  market('Lucia', 'lucia', { district: 'prenzlauer-berg', free: true }),
  market('Rotes Rathaus', 'rathaus', { district: 'mitte', free: true }),
]

const stubs = {
  EventRow: defineComponent({
    props: { event: Object },
    setup: (props) => () => h('article', props.event?.title),
  }),
  VenueMap: defineComponent({
    props: { pins: Array },
    setup: (props) => () => h('div', { 'data-testid': 'map', 'data-pins': props.pins?.length }),
  }),
}

let mounted: VueWrapper | undefined
let router: Router

async function mountWith(content: EventSummary[], path = '/de/christmas-markets') {
  searchEvents.mockResolvedValue({ content, totalElements: content.length } as EventPage)
  const Page = defineComponent({ render: () => h('p') })
  router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)*', component: Page }],
  })
  await router.push(path)
  setI18nLocale('de')
  mounted = mount(ChristmasMarketsView, { global: { plugins: [router], stubs } })
  await flushPromises()
  return mounted
}

afterEach(() => {
  mounted?.unmount()
  mounted = undefined
  searchEvents.mockReset()
  setI18nLocale('en')
})

describe('ChristmasMarketsView', () => {
  it('asks for the running Christmas markets and lists them by name', async () => {
    const wrapper = await mountWith([
      market('Weihnachtszauber am Gendarmenmarkt', 'b'),
      market('Lucia Weihnachtsmarkt', 'a'),
    ])

    expect(searchEvents).toHaveBeenCalledWith(
      expect.objectContaining({ feature: ['christmas-market'], running: true, size: 100 }),
    )
    expect(wrapper.findAll('article').map((row) => row.text())).toEqual([
      'Lucia Weihnachtsmarkt',
      'Weihnachtszauber am Gendarmenmarkt',
    ])
    expect(wrapper.text()).toContain('2 Weihnachtsmärkte')
  })

  it('says when the markets come back outside the season', async () => {
    const wrapper = await mountWith([])

    expect(wrapper.text()).toContain('Gerade gibt es keine Weihnachtsmärkte.')
    expect(wrapper.find('[data-testid="map"]').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Eintritt frei')
  })

  function titles(wrapper: VueWrapper): string[] {
    return wrapper.findAll('article').map((row) => row.text())
  }

  function pinCount(wrapper: VueWrapper): string | undefined {
    return wrapper.find('[data-testid="map"]').attributes('data-pins')
  }

  it('keeps the free markets in the list, the count and the map', async () => {
    const wrapper = await mountWith(SEASON, '/de/christmas-markets?free=true')

    expect(titles(wrapper)).toEqual(['Altstadt Spandau', 'Lucia', 'Rotes Rathaus'])
    expect(wrapper.text()).toContain('3 Weihnachtsmärkte')
    expect(pinCount(wrapper)).toBe('3')
  })

  it('keeps the markets in the chosen districts', async () => {
    const wrapper = await mountWith(SEASON, '/de/christmas-markets?district=mitte&district=spandau')

    expect(titles(wrapper)).toEqual(['Altstadt Spandau', 'Gendarmenmarkt', 'Rotes Rathaus'])
    expect(pinCount(wrapper)).toBe('3')
  })

  it('combines free entry and a district', async () => {
    const wrapper = await mountWith(SEASON, '/de/christmas-markets?free=true&district=mitte')

    expect(titles(wrapper)).toEqual(['Rotes Rathaus'])
    expect(wrapper.text()).toContain('1 Weihnachtsmarkt')
  })

  it('offers only the districts a market is in, counted under the free filter', async () => {
    const wrapper = await mountWith(SEASON, '/de/christmas-markets?free=true')

    const filter = wrapper.findComponent({ name: 'MultiSelectFilter' })
    expect(filter.props('options')).toEqual([
      { value: 'mitte', label: 'Mitte' },
      { value: 'prenzlauer-berg', label: 'Prenzlauer Berg' },
      { value: 'spandau', label: 'Spandau' },
    ])
    expect(filter.props('counts')).toEqual({ mitte: 1, 'prenzlauer-berg': 1, spandau: 1 })
  })

  it('says when nothing matches and clears the filters', async () => {
    const wrapper = await mountWith(SEASON, '/de/christmas-markets?free=true&district=wedding')

    expect(titles(wrapper)).toEqual([])
    expect(wrapper.find('[data-testid="map"]').exists()).toBe(false)
    expect(wrapper.text()).toContain('Zu diesen Filtern gibt es keinen Weihnachtsmarkt.')

    const clear = wrapper
      .findAll('button')
      .find((button) => button.text() === 'Alle Filter zurücksetzen')
    await clear?.trigger('click')
    await flushPromises()

    expect(router.currentRoute.value.query).toEqual({})
    expect(titles(wrapper)).toHaveLength(4)
  })

  it('writes the filters into the URL', async () => {
    const wrapper = await mountWith(SEASON)

    const toggle = wrapper.findAll('button').find((button) => button.text() === 'Eintritt frei')
    expect(toggle?.attributes('aria-pressed')).toBe('false')
    await toggle?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ free: 'true' })
    expect(toggle?.attributes('aria-pressed')).toBe('true')

    wrapper.findComponent({ name: 'MultiSelectFilter' }).vm.$emit('change', ['mitte'])
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ free: 'true', district: ['mitte'] })
    expect(titles(wrapper)).toEqual(['Rotes Rathaus'])

    await toggle?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ district: ['mitte'] })
  })
})
