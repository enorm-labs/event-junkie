import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import type { EventSummary } from '@/api/types'
import { setI18nLocale } from '@/i18n'

const { getMock } = vi.hoisted(() => ({
  getMock: vi.fn<(path: string, init: { params: { query: unknown } }) => Promise<unknown>>(),
}))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: WeekView } = await import('@/views/WeekView.vue')

const Page = defineComponent({ render: () => h('p') })

const stubs = {
  EventCard: { template: '<article :data-slug="event.slug" />', props: ['event'] },
  EventRow: { template: '<article :data-slug="event.slug" />', props: ['event'] },
}

/** A busy Friday, a quiet Tuesday, and a long title that must not be cut. */
const friday: EventSummary[] = Array.from({ length: 40 }, (_, index) => ({
  slug: `friday-${index}`,
  eventDate: '2026-10-09',
}))
const tuesday: EventSummary = {
  slug: 'tuesday',
  eventDate: '2026-10-06',
  title: 'A very long title '.repeat(12),
  status: 'CANCELLED',
}

let wrapper: VueWrapper | undefined

async function mountAt(path: string) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/week/:isoWeek', name: 'week', component: Page },
      { path: '/:locale(en|de)/:rest(.*)', component: Page },
    ],
  })
  await router.push(path)
  setI18nLocale(path.startsWith('/de') ? 'de' : 'en')
  wrapper = mount(WeekView, { global: { plugins: [router], stubs } })
  await flushPromises()
}

function sectionSlugs(): string[][] {
  return wrapper!
    .findAll('section')
    .map((section) => section.findAll('article').map((card) => card.attributes('data-slug') ?? ''))
}

describe('WeekView', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    // Friday 9 October 2026, 20:00 in Berlin: week 41.
    vi.setSystemTime(new Date('2026-10-09T18:00:00Z'))
    getMock.mockReset()
    getMock.mockResolvedValue([tuesday, ...friday])
  })

  afterEach(() => {
    wrapper?.unmount()
    wrapper = undefined
    setI18nLocale('en')
    vi.useRealTimers()
  })

  it('reads the calendar for Monday to Sunday, and lists every day under its own heading', async () => {
    await mountAt('/en/week/2026-41')

    expect(getMock).toHaveBeenCalledWith('/api/events/calendar', {
      params: { query: { from: '2026-10-05', to: '2026-10-11' } },
    })
    expect(wrapper!.findAll('h2').map((heading) => heading.text())).toEqual([
      'Monday 5 October',
      'Tuesday 6 October',
      'Wednesday 7 October',
      'Thursday 8 October',
      'Friday 9 October',
      'Saturday 10 October',
      'Sunday 11 October',
    ])
    expect(wrapper!.find('h1').text()).toBe('Week 41 in Berlin')
    expect(wrapper!.text()).toContain('This week')
  })

  it('shows twelve events of a busy day and links the rest to the events list for that day', async () => {
    await mountAt('/en/week/2026-41')

    const slugs = sectionSlugs()
    expect(slugs[1]).toEqual(['tuesday'])
    expect(slugs[4]).toEqual(friday.slice(0, 12).map((event) => event.slug))
    const more = wrapper!.findAll('section')[4]!.find('a[href^="/en/events"]')
    expect(more.text()).toBe('All 40 events')
    expect(more.attributes('href')).toBe('/en/events?from=2026-10-09&to=2026-10-09')
    // A day with no more than twelve has no such link.
    expect(wrapper!.findAll('section')[1]!.find('a[href^="/en/events"]').exists()).toBe(false)
  })

  it('marks a past week as past, and links back to this week', async () => {
    await mountAt('/de/week/2026-40')

    expect(wrapper!.find('[data-testid="past-week"]').text()).toBe(
      'Diese Woche ist vorbei. Das war los.',
    )
    expect(wrapper!.find('h1').text()).toBe('Woche 40 in Berlin')
    const links = wrapper!.findAll('nav a').map((link) => link.attributes('href'))
    expect(links).toEqual(['/de/week/2026-39', '/de/week/2026-41', '/de/week/2026-41'])
  })

  it('does not mark this week or a later one as past', async () => {
    await mountAt('/en/week/2026-42')
    expect(wrapper!.find('[data-testid="past-week"]').exists()).toBe(false)
    expect(wrapper!.findAll('nav a').map((link) => link.attributes('href'))).toEqual([
      '/en/week/2026-41',
      '/en/week/2026-41',
      '/en/week/2026-43',
    ])
  })

  it('links this week to the events list from today to Sunday, to filter it there', async () => {
    await mountAt('/en/week/2026-41')
    const link = wrapper!.find('[data-testid="filter-week"]')
    expect(link.text()).toBe('Filter this week')
    expect(link.attributes('href')).toBe('/en/events?from=2026-10-09&to=2026-10-11')
  })

  it('links a later week to the events list from Monday to Sunday, in German too', async () => {
    await mountAt('/de/week/2026-42')
    const link = wrapper!.find('[data-testid="filter-week"]')
    expect(link.text()).toBe('Diese Woche filtern')
    expect(link.attributes('href')).toBe('/de/events?from=2026-10-12&to=2026-10-18')
  })

  it('has no filter link on a past week, as the events list shows no past events', async () => {
    await mountAt('/en/week/2026-40')
    expect(wrapper!.find('[data-testid="filter-week"]').exists()).toBe(false)
  })

  it('names the page after the week, with its dates', async () => {
    await mountAt('/en/week/2026-41')
    expect(document.title).toBe('Week 41 in Berlin · Event Junkie')
  })
})
