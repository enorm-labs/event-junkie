import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import type { EventPage, EventSummary } from '@/api/types'
import type { EventSearchParams } from '@/composables/useEvents'
import { setI18nLocale } from '@/i18n'

const searchEvents = vi.fn<(params: EventSearchParams) => Promise<EventPage>>()

vi.mock('@/composables/useEvents', () => ({
  searchEvents: (params: EventSearchParams) => searchEvents(params),
}))

const { default: ChristmasMarketsView } = await import('@/views/ChristmasMarketsView.vue')

function market(title: string, slug: string): EventSummary {
  return {
    slug,
    title,
    eventDate: '2026-11-23',
    endDate: '2026-12-31',
    venue: { slug: `${slug}-venue`, name: title, latitude: 52.51, longitude: 13.39 },
  } as EventSummary
}

const stubs = {
  EventRow: defineComponent({
    props: { event: Object },
    setup: (props) => () => h('article', props.event?.title),
  }),
  VenueMap: { template: '<div data-testid="map" />' },
}

let mounted: VueWrapper | undefined

async function mountWith(content: EventSummary[]) {
  searchEvents.mockResolvedValue({ content, totalElements: content.length } as EventPage)
  const Page = defineComponent({ render: () => h('p') })
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/:locale(en|de)/:rest(.*)*', component: Page }],
  })
  await router.push('/de/christmas-markets')
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
  })
})
