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
    setup: (props) => () => h('article', { 'data-slug': props.event?.slug }, props.event?.title),
  }),
  VenueMap: defineComponent({
    name: 'VenueMap',
    props: { pins: Array, selected: String },
    emits: ['update:selected', 'unavailable'],
    setup:
      (props, { slots }) =>
      () =>
        h('div', { 'data-testid': 'map', 'data-pins': props.pins?.length }, slots.default?.()),
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

describe('ChristmasMarketsView by day', () => {
  function dated(
    title: string,
    eventDate: string,
    endDate: string | null,
    district = 'mitte',
  ): EventSummary {
    const slug = title.toLowerCase().replace(/ /g, '-')
    return { ...market(title, slug, { district }), eventDate, endDate }
  }

  // A daily run, a market entered once per Advent weekend, a one-day market and one closed weekend.
  const MARKETS = [
    dated('Dahlem 2', '2026-12-12', '2026-12-13'),
    dated('Dahlem 3', '2026-12-19', '2026-12-20'),
    dated('Gendarmenmarkt', '2026-11-23', '2026-12-31'),
    dated('Weddingmarkt', '2026-12-13', null, 'wedding'),
  ]

  // 23:30 in Berlin on Saturday 12 December, still that Saturday there.
  const SATURDAY_NIGHT = '2026-12-12T22:30:00Z'
  // 00:30 in Berlin on Sunday 13 December, while UTC is still on the Saturday.
  const SUNDAY_PAST_MIDNIGHT = '2026-12-12T23:30:00Z'
  const WEDNESDAY = '2026-12-09T10:00:00Z'

  function at(instant: string) {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(instant)
  }

  afterEach(() => {
    vi.useRealTimers()
  })

  function titles(wrapper: VueWrapper): string[] {
    return wrapper.findAll('article').map((row) => row.text())
  }

  it('keeps the markets open today in Berlin at 23:30', async () => {
    at(SATURDAY_NIGHT)
    const wrapper = await mountWith(MARKETS, '/de/christmas-markets?day=today')

    expect(titles(wrapper)).toEqual(['Dahlem 2', 'Gendarmenmarkt'])
    expect(wrapper.find('[data-testid="map"]').attributes('data-pins')).toBe('2')
  })

  it('takes the Berlin date past midnight, when UTC is a day behind', async () => {
    at(SUNDAY_PAST_MIDNIGHT)
    const wrapper = await mountWith(MARKETS, '/de/christmas-markets?day=today')

    expect(titles(wrapper)).toEqual(['Dahlem 2', 'Gendarmenmarkt', 'Weddingmarkt'])
  })

  it('reads this weekend as Saturday and Sunday on a weekday', async () => {
    at(WEDNESDAY)
    const wrapper = await mountWith(MARKETS, '/de/christmas-markets?day=weekend')

    expect(titles(wrapper)).toEqual(['Dahlem 2', 'Gendarmenmarkt', 'Weddingmarkt'])
  })

  it('reads this weekend as that Sunday alone on a Sunday', async () => {
    at('2026-12-20T10:00:00Z')
    const wrapper = await mountWith(MARKETS, '/de/christmas-markets?day=weekend')

    expect(titles(wrapper)).toEqual(['Dahlem 3', 'Gendarmenmarkt'])
  })

  it.each([
    ['a Monday of the daily run', '2026-12-14', ['Gendarmenmarkt']],
    ['the day of a one-day market', '2026-12-13', ['Dahlem 2', 'Gendarmenmarkt', 'Weddingmarkt']],
  ])('keeps the markets open on %s', async (_, day, expected) => {
    at(WEDNESDAY)
    const wrapper = await mountWith(MARKETS, `/de/christmas-markets?day=${day}`)

    expect(titles(wrapper)).toEqual(expected)
  })

  it.each([
    ['before the season', '2026-11-20'],
    ['after the season', '2027-01-02'],
  ])('finds no market on a date %s', async (_, day) => {
    at('2026-11-14T10:00:00Z')
    const wrapper = await mountWith(MARKETS, `/de/christmas-markets?day=${day}`)

    expect(titles(wrapper)).toEqual([])
    expect(wrapper.text()).toContain('Zu diesen Filtern gibt es keinen Weihnachtsmarkt.')
  })

  it('ignores a date before today, whose markets the page does not load', async () => {
    at(WEDNESDAY)
    const wrapper = await mountWith(MARKETS, '/de/christmas-markets?day=2026-12-01')

    expect(titles(wrapper)).toHaveLength(4)
    expect(wrapper.find('input[type="date"]').element).toHaveProperty('value', '')
  })

  it('bounds the date input by today and the last market day', async () => {
    at(WEDNESDAY)
    const wrapper = await mountWith(MARKETS)

    const input = wrapper.find('input[type="date"]')
    expect(input.attributes('min')).toBe('2026-12-09')
    expect(input.attributes('max')).toBe('2026-12-31')
  })

  it('writes the choice into the URL and combines it with the other filters', async () => {
    at(WEDNESDAY)
    const wrapper = await mountWith(MARKETS, '/de/christmas-markets?district=mitte')

    const weekend = wrapper.findAll('button').find((b) => b.text() === 'Dieses Wochenende')
    await weekend?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ district: 'mitte', day: 'weekend' })
    expect(weekend?.attributes('aria-pressed')).toBe('true')
    expect(titles(wrapper)).toEqual(['Dahlem 2', 'Gendarmenmarkt'])

    const input = wrapper.find('input[type="date"]')
    await input.setValue('2026-12-19')
    await input.trigger('change')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ district: 'mitte', day: '2026-12-19' })
    expect(weekend?.attributes('aria-pressed')).toBe('false')
    expect(titles(wrapper)).toEqual(['Dahlem 3', 'Gendarmenmarkt'])

    const today = wrapper.findAll('button').find((b) => b.text() === 'Heute')
    await today?.trigger('click')
    await flushPromises()
    expect(input.element).toHaveProperty('value', '')
    await today?.trigger('click')
    await flushPromises()
    expect(router.currentRoute.value.query).toEqual({ district: 'mitte' })
    expect(titles(wrapper)).toHaveLength(3)
  })
})

describe('ChristmasMarketsView with a market entered per weekend', () => {
  function weekend(slug: string, eventDate: string, endDate: string, free = false): EventSummary {
    return {
      ...market('Adventsmarkt Domäne', slug, { free, district: 'zehlendorf' }),
      eventDate,
      endDate,
      venue: {
        slug: 'domaene',
        name: 'Domäne',
        district: 'zehlendorf',
        latitude: 52.4,
        longitude: 13.2,
      },
    } as EventSummary
  }

  const DOMAENE = [
    weekend('domaene-2', '2026-12-05', '2026-12-06'),
    weekend('domaene-1', '2026-11-28', '2026-11-29'),
    weekend('domaene-3', '2026-12-12', '2026-12-13', true),
    weekend('domaene-4', '2026-12-19', '2026-12-20'),
  ]
  const EVENTS = [...DOMAENE, market('Gendarmenmarkt', 'gendarmen')]

  afterEach(() => {
    vi.useRealTimers()
  })

  async function mountOn(instant: string, query = '') {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(instant)
    return mountWith(EVENTS, `/de/christmas-markets${query}`)
  }

  function rows(wrapper: VueWrapper): (string | undefined)[] {
    return wrapper.findAll('article').map((row) => row.attributes('data-slug'))
  }

  it('lists the market once, with its next weekend, and counts and pins it once', async () => {
    const wrapper = await mountOn('2026-11-30T10:00:00Z')

    expect(rows(wrapper)).toEqual(['domaene-2', 'gendarmen'])
    expect(wrapper.text()).toContain('2 Weihnachtsmärkte')
    expect(wrapper.find('[data-testid="map"]').attributes('data-pins')).toBe('2')
  })

  it('shows the weekend of the chosen day', async () => {
    const wrapper = await mountOn('2026-11-30T10:00:00Z', '?day=2026-12-19')

    expect(rows(wrapper)).toEqual(['domaene-4', 'gendarmen'])
  })

  it('keeps the market when one of its weekends is free', async () => {
    const wrapper = await mountOn('2026-11-30T10:00:00Z', '?free=true')

    expect(rows(wrapper)).toEqual(['domaene-3'])
  })

  it('lists every weekend in the map panel and links the next one', async () => {
    const wrapper = await mountOn('2026-11-30T10:00:00Z')

    wrapper.findComponent({ name: 'VenueMap' }).vm.$emit('update:selected', 'domaene')
    await flushPromises()

    const panel = wrapper.findComponent({ name: 'MapPanel' })
    expect(panel.text().split(' · ')).toHaveLength(4)
    expect(panel.find('a[href="/de/events/domaene-2"]').exists()).toBe(true)
  })
})
