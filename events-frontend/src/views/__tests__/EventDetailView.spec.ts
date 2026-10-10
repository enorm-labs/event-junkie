import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import type { EventDetail } from '@/api/types'
import { i18n } from '@/i18n'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn<(path: string) => Promise<unknown>>() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: EventDetailView } = await import('@/views/EventDetailView.vue')

const Page = defineComponent({ render: () => h('p') })

const TAG = 'utm_source=event-junkie.de&utm_medium=referral'

const night: EventDetail = {
  slug: 'a-night',
  title: 'A Night',
  eventDate: '2099-06-12',
  ticketUrl: 'https://tickets.example/a-night',
  sourceUrl: 'https://katerblau.de/programm?id=7',
  lineup: [
    {
      artist: {
        slug: 'mock-artist',
        name: 'Mock Artist',
        bandcampUrl: 'https://mock-artist.bandcamp.com/',
      },
      role: 'HEADLINER',
    },
  ],
}

let wrapper: VueWrapper | undefined

afterEach(() => {
  i18n.global.locale.value = 'en'
  vi.useRealTimers()
  wrapper?.unmount()
  wrapper = undefined
  getMock.mockReset()
})

async function mountEvent(event: EventDetail, locale: 'en' | 'de' = 'en') {
  getMock.mockImplementation((path: string) =>
    Promise.resolve(path === '/api/events/{slug}' ? event : []),
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/events/:slug', component: EventDetailView },
      { path: '/:locale(en|de)/:rest(.*)', component: Page },
    ],
  })
  i18n.global.locale.value = locale
  await router.push(`/${locale}/events/${event.slug}`)
  wrapper = mount(EventDetailView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

function hrefOf(view: VueWrapper, text: string): string | undefined {
  return view
    .findAll('a')
    .find((link) => link.text() === text)
    ?.attributes('href')
}

describe('EventDetailView', () => {
  it('tags the ticket and source links as referrals from this site (#2770)', async () => {
    const view = await mountEvent(night)

    expect(hrefOf(view, 'Buy tickets')).toBe(`https://tickets.example/a-night?${TAG}`)
    expect(hrefOf(view, 'Event page')).toBe(`https://katerblau.de/programm?id=7&${TAG}`)
  })

  it('offers no tickets for a cancelled night, but keeps the source page for refunds (#2916)', async () => {
    const view = await mountEvent({ ...night, status: 'CANCELLED' })

    expect(hrefOf(view, 'Buy tickets')).toBeUndefined()
    expect(hrefOf(view, 'Event page')).toBeDefined()
  })

  // A postponed show's tickets usually stay valid for the new date.
  for (const status of ['SCHEDULED', 'POSTPONED'] as const) {
    it(`offers tickets for a ${status} night`, async () => {
      const view = await mountEvent({ ...night, status })

      expect(hrefOf(view, 'Buy tickets')).toBeDefined()
    })
  }

  const priced: EventDetail = { ...night, pricePresale: 18, priceCurrency: 'EUR' }

  it('shows no price block for a cancelled night (#2916)', async () => {
    const view = await mountEvent({ ...priced, status: 'CANCELLED' })

    expect(view.text()).not.toContain('Presale')
  })

  for (const status of ['SCHEDULED', 'POSTPONED'] as const) {
    it(`shows the price block for a ${status} night`, async () => {
      const view = await mountEvent({ ...priced, status })

      expect(view.text()).toContain('Presale')
    })
  }

  it('says the price is not announced when nothing is known (#2963)', async () => {
    const view = await mountEvent(night)

    expect(view.text()).toContain('Price not announced')
  })

  it('says free entry in the tickets block for a free night', async () => {
    const view = await mountEvent({ ...night, free: true })

    expect(view.text()).toContain('Free entry')
    expect(view.text()).not.toContain('Price not announced')
  })

  it('shows a price note alone without claiming the price is unknown', async () => {
    const view = await mountEvent({ ...night, priceNote: 'Pay what you can' })

    expect(view.text()).toContain('Pay what you can')
    expect(view.text()).not.toContain('Price not announced')
  })

  it('orders poster, description, facts, then lineup', async () => {
    const view = await mountEvent({
      ...night,
      imageUrl: 'https://img.example/a.jpg',
      description: 'A long night of records.',
    })

    const html = view.html()
    const order = ['img.example', 'A long night of records.', 'Tickets', 'Mock Artist']
    const positions = order.map((text) => html.indexOf(text))
    expect(positions.every((at) => at >= 0)).toBe(true)
    expect(positions).toEqual([...positions].sort((a, b) => a - b))
  })

  it('credits each source that filled a field, linked by its host, and no source on a plain event (#2593)', async () => {
    const view = await mountEvent({
      ...night,
      enrichmentSources: [
        { sourceUrl: 'https://puschen.example/alpha', fields: ['lineup'] },
        { sourceUrl: 'https://fans.example/alpha', fields: ['genre'] },
      ],
    })

    expect(view.text()).toContain('Further details from puschen.example, fans.example')
    expect(hrefOf(view, 'puschen.example')).toBe('https://puschen.example/alpha')
    expect(hrefOf(view, 'fans.example')).toBe('https://fans.example/alpha')
    view.unmount()

    expect((await mountEvent(night)).text()).not.toContain('Further details from')
  })

  it('shows the features in display order, each a link to the list filtered by it (#2631)', async () => {
    const view = await mountEvent({ ...night, features: ['open-end', 'flinta-only'] })

    const section = view.get('[data-testid="event-features"]')
    expect(section.findAll('a').map((link) => link.text())).toEqual(['FLINTA* only', 'Open end'])
    expect(hrefOf(view, 'FLINTA* only')).toBe('/en/events?feature=flinta-only')
    expect(section.text()).toContain("As the event's own text states it.")
  })

  it('shows no feature section for a night that states none', async () => {
    const view = await mountEvent({ ...night, features: [] })

    expect(view.find('[data-testid="event-features"]').exists()).toBe(false)
  })

  it("leaves an artist's Bandcamp link untagged", async () => {
    const view = await mountEvent(night)

    expect(hrefOf(view, 'Bandcamp')).toBe('https://mock-artist.bandcamp.com/')
  })

  it('says the end was not announced in the When block, not under the buttons', async () => {
    const view = await mountEvent({ ...night, startTime: '23:00:00' })

    expect(view.text()).toContain('End not announced')
    expect(view.text()).not.toContain('the calendar entry ends at our estimate')
  })

  it('marks an estimated start in the When block only, with no note under the buttons', async () => {
    const view = await mountEvent({ ...night, assumedStartTime: '23:00:00' })

    const when = view.findAll('p').map((p) => p.text())
    expect(when).toContain('Start ~23:00 · our estimate')
    expect(view.text()).not.toContain('The start time is our estimate.')
    expect(view.text()).not.toContain('the calendar entry ends at our estimate')
  })

  describe('venue change (#2725)', () => {
    const moved: EventDetail = {
      ...night,
      venue: { slug: 'festsaal-kreuzberg', name: 'Festsaal Kreuzberg' },
      changes: [
        { field: 'START_TIME', from: '22:00', to: '23:00', seenAt: '2026-10-08T03:10:00+02:00' },
        {
          field: 'VENUE',
          from: 'Lido',
          to: 'Festsaal Kreuzberg',
          seenAt: '2026-10-01T03:10:00+02:00',
        },
      ],
    }

    /** The fact block whose label is `label`, as text. */
    function block(view: VueWrapper, label: string): string {
      const found = view.findAll('section > div').find((div) => div.text().startsWith(label))
      expect(found).toBeDefined()
      return found!.text()
    }

    for (const [locale, venue, when, line] of [
      ['en', 'Venue', 'When', 'Moved from Lido to Festsaal Kreuzberg · 8 days ago'],
      ['de', 'Location', 'Wann', 'Verlegt von Lido nach Festsaal Kreuzberg · vor 8 Tagen'],
    ] as const) {
      it(`shows the move under the venue's name, not in the When block (${locale})`, async () => {
        vi.useFakeTimers({ toFake: ['Date'] })
        vi.setSystemTime(new Date('2026-10-09T12:00:00+02:00'))
        const view = await mountEvent(moved, locale)

        expect(block(view, venue)).toContain(`Festsaal Kreuzberg${line}`)
        expect(block(view, when)).not.toContain('Lido')
        expect(block(view, when)).toContain('22:00')
      })
    }
  })
})
