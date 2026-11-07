import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import type { EventDetail, EventSummary } from '@/api/types'
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

async function mountEvent(
  event: EventDetail,
  locale: 'en' | 'de' = 'en',
  related: EventSummary[] = [],
) {
  getMock.mockImplementation((path: string) =>
    Promise.resolve(
      path === '/api/events/{slug}' ? event : path.endsWith('/related') ? related : [],
    ),
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
  it('links the festival to its events in both locales, and shows nothing without one (#3273)', async () => {
    const festival = { slug: 'fete-de-la-musique-2027', name: 'Fête de la Musique 2027' }
    const plain = await mountEvent(night)
    expect(plain.find('[data-testid="festival-link"]').exists()).toBe(false)
    plain.unmount()

    const en = await mountEvent({ ...night, festival })
    const link = en.get('[data-testid="festival-link"]')
    expect(link.text()).toBe('Fête de la Musique 2027')
    expect(link.attributes('href')).toBe('/en/events?festival=fete-de-la-musique-2027')
    expect(link.attributes('aria-label')).toBe('All events of Fête de la Musique 2027')
    en.unmount()

    const de = await mountEvent({ ...night, festival }, 'de')
    expect(de.get('[data-testid="festival-link"]').attributes('href')).toBe(
      '/de/events?festival=fete-de-la-musique-2027',
    )
  })

  it('puts our pick on the poster corner of a featured night only, in both locales (#3065)', async () => {
    const poster = { ...night, imageUrl: 'https://img.example/a.jpg' }
    const plain = await mountEvent(poster)
    expect(plain.find('[data-testid="pick-badge"]').exists()).toBe(false)
    plain.unmount()

    const en = await mountEvent({ ...poster, featured: true })
    const pill = en.get('[data-testid="pick-badge"]')
    expect(pill.text()).toBe('Our pick')
    expect(pill.classes()).toEqual(expect.arrayContaining(['absolute', 'top-3', 'left-3']))
    // A sibling of the image, inside the poster's box, not inside the picture.
    expect(pill.element.parentElement?.querySelector('img')).not.toBeNull()
    expect(pill.element.closest('picture')).toBeNull()
    en.unmount()

    const de = await mountEvent({ ...poster, featured: true }, 'de')
    expect(de.get('[data-testid="pick-badge"]').text()).toBe('Unser Tipp')
  })

  it('puts our pick in the header of a featured night with no poster (#3065)', async () => {
    const view = await mountEvent({ ...night, featured: true })
    const pill = view.get('[data-testid="pick-badge"]')

    expect(pill.element.closest('header')).not.toBeNull()
    expect(pill.classes()).not.toContain('absolute')
    expect(view.findAll('[data-testid="pick-badge"]')).toHaveLength(1)
  })

  it('keeps our pick on a cancelled featured night (#3065)', async () => {
    const view = await mountEvent({ ...night, featured: true, status: 'CANCELLED' })

    expect(view.find('[data-testid="pick-badge"]').exists()).toBe(true)
  })

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

  it('strikes the date of a postponed night in the hero, drops its time, and names the new date in the pill (#3093)', async () => {
    const view = await mountEvent({
      ...night,
      status: 'POSTPONED',
      startTime: '20:00:00',
      postponedTo: { slug: '2100-05-08-a-night', eventDate: '2100-05-08' },
    })
    const hero = view.find('h1').element.parentElement!.textContent ?? ''

    expect(view.find('del').text()).toBe('Fri, 12 Jun 2099')
    expect(hero).toContain('Postponed to Sat, 8 May 2100')
    expect(hero).not.toContain('20:00')
  })

  it('names the house and the new date of a show that moved both in the pill (#3613)', async () => {
    const view = await mountEvent({
      ...night,
      status: 'RELOCATED',
      relocatedTo: 'Hole 44',
      relocatedOn: '2100-04-13',
    })
    const hero = view.find('h1').element.parentElement!.textContent ?? ''

    expect(hero).toContain('Moved to Hole 44, 13 April 2100')
  })

  const relocated: EventDetail = {
    ...night,
    status: 'RELOCATED',
    relocatedTo: 'huxleys',
    venue: {
      slug: 'columbiahalle',
      name: 'Columbiahalle',
      address: 'Columbiadamm 13-21',
      city: 'Berlin',
      latitude: 52.4839,
      longitude: 13.3911,
    },
  }
  const huxleys = {
    venueName: 'Huxleys Neue Welt',
    venueSlug: 'huxleys-neue-welt',
    eventSlug: '2099-06-12-huxleys-neue-welt-a-night',
    address: 'Hasenheide 107-113',
    city: 'Berlin',
    latitude: 52.4862,
    longitude: 13.4206,
  }

  it('names the house by its own name and links its night under the venue', async () => {
    const view = await mountEvent({ ...relocated, movedTo: huxleys })
    const hero = view.find('h1').element.parentElement!.textContent ?? ''
    const movedTo = view.find('[data-testid="moved-to"]')

    expect(hero).toContain('Moved to Huxleys Neue Welt')
    expect(movedTo.text()).toBe('Moved to: Huxleys Neue Welt')
    expect(movedTo.find('a').attributes('href')).toBe(
      '/en/events/2099-06-12-huxleys-neue-welt-a-night',
    )
  })

  it('shows the new house and its address first, the old one muted below, and routes to the new one', async () => {
    const view = await mountEvent({ ...relocated, movedTo: huxleys })
    const text = view.text()
    const movedFrom = view.find('[data-testid="moved-from"]')

    expect(text).toContain('Hasenheide 107-113, Berlin')
    expect(text).not.toContain('Columbiadamm')
    expect(text.indexOf('Huxleys Neue Welt')).toBeLessThan(text.indexOf('Originally:'))
    expect(movedFrom.text()).toBe('Originally: Columbiahalle')
    expect(movedFrom.classes()).toContain('text-muted-foreground')
    expect(movedFrom.find('a').attributes('href')).toBe('/en/venues/columbiahalle')
    expect(view.find('[data-testid="venue-directions-link"]').attributes('href')).toContain(
      '52.4862,13.4206',
    )
  })

  const stops = [{ name: 'U Rathaus Neukölln', lines: ['U7'], walkingMetres: 300 }]

  it("lists the venue's nearest stations beside the route link", async () => {
    const view = await mountEvent({
      ...night,
      venue: { slug: 'huxleys', name: 'Huxleys Neue Welt' },
      nearbyStops: stops,
    })

    expect(view.get('[data-testid="nearby-stops"]').text()).toBe(
      'U Rathaus Neukölln · U7 · 300 m on foot',
    )
  })

  it("lists no stations for a relocated night, because they are the old address's", async () => {
    const view = await mountEvent({ ...relocated, movedTo: huxleys, nearbyStops: stops })

    expect(view.find('[data-testid="nearby-stops"]').exists()).toBe(false)
  })

  it('says it in German too', async () => {
    const view = await mountEvent({ ...relocated, movedTo: huxleys }, 'de')

    expect(view.find('[data-testid="moved-to"]').text()).toBe('Verlegt nach: Huxleys Neue Welt')
    expect(view.find('[data-testid="moved-from"]').text()).toBe('Ursprünglich: Columbiahalle')
  })

  it("links the house's page when the house does not list the night", async () => {
    const view = await mountEvent({
      ...relocated,
      relocatedTo: 'Hole',
      movedTo: { venueName: 'Hole44', venueSlug: 'hole-44', eventSlug: null },
    })

    expect(view.find('[data-testid="moved-to"] a').attributes('href')).toBe('/en/venues/hole-44')
  })

  it('names a house we do not list as text, keeps the old address and offers no route', async () => {
    const view = await mountEvent({ ...relocated, relocatedTo: 'rbb Sendesaal' })
    const hero = view.find('h1').element.parentElement!.textContent ?? ''
    const movedTo = view.find('[data-testid="moved-to"]')

    expect(hero).toContain('Moved to rbb Sendesaal')
    expect(movedTo.text()).toBe('Moved to: rbb Sendesaal')
    expect(movedTo.find('a').exists()).toBe(false)
    expect(view.text()).toContain('Columbiadamm 13-21, Berlin')
    expect(view.find('[data-testid="venue-directions-link"]').exists()).toBe(false)
  })

  it('says the new house is not announced, and offers no route, when the note names none', async () => {
    const view = await mountEvent({ ...relocated, relocatedTo: null })

    expect(view.find('[data-testid="moved-unannounced"]').text()).toBe(
      'Moved. The new venue is not announced yet.',
    )
    expect(view.find('[data-testid="moved-to"]').exists()).toBe(false)
    expect(view.text()).toContain('Columbiahalle')
    expect(view.find('[data-testid="venue-directions-link"]').exists()).toBe(false)
  })

  it('routes a scheduled night to its own venue', async () => {
    const view = await mountEvent({ ...relocated, status: 'SCHEDULED', relocatedTo: null })

    expect(view.find('[data-testid="venue-directions-link"]').attributes('href')).toContain(
      '52.4839,13.3911',
    )
    expect(view.find('[data-testid="moved-unannounced"]').exists()).toBe(false)
  })

  it('strikes the date of a cancelled night in the hero and drops its time', async () => {
    const view = await mountEvent({ ...night, status: 'CANCELLED', startTime: '20:00:00' })
    const hero = view.find('h1').element.parentElement!.textContent ?? ''

    expect(view.find('del').text()).toBe('Fri, 12 Jun 2099')
    expect(hero).toContain('Cancelled')
    expect(hero).not.toContain('20:00')
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

  it('orders poster, description, lineup, then facts', async () => {
    const view = await mountEvent({
      ...night,
      imageUrl: 'https://img.example/a.jpg',
      description: 'A long night of records.',
    })

    const html = view.html()
    const order = ['img.example', 'A long night of records.', 'Mock Artist', 'Tickets']
    const positions = order.map((text) => html.indexOf(text))
    expect(positions.every((at) => at >= 0)).toBe(true)
    expect(positions).toEqual([...positions].sort((a, b) => a - b))
  })

  it('folds a lineup over ten acts to the first eight until the visitor asks for all', async () => {
    const lineup = Array.from({ length: 11 }, (_, index) => ({
      artist: { name: `Act ${index + 1}` },
      role: 'DJ' as const,
    }))
    const view = await mountEvent({ ...night, lineup })

    expect(view.text()).toContain('Act 8')
    expect(view.text()).not.toContain('Act 9')
    await view.get('[data-testid="lineup-show-all"]').trigger('click')
    expect(view.text()).toContain('Act 11')
    expect(view.find('[data-testid="lineup-show-all"]').exists()).toBe(false)
  })

  it('folds a running order across its floors in print order', async () => {
    const lineup = Array.from({ length: 11 }, (_, index) => ({
      artist: { name: `Act ${index + 1}` },
      role: 'DJ' as const,
      stage: index < 6 ? 'Main' : 'Garden',
      setStart: `2099-06-12T${String(12 + index).padStart(2, '0')}:00:00+02:00`,
    }))
    const view = await mountEvent({ ...night, lineup })

    expect(view.text()).toContain('Garden')
    expect(view.text()).toContain('Act 8')
    expect(view.text()).not.toContain('Act 9')
    await view.get('[data-testid="lineup-show-all"]').trigger('click')
    expect(view.text()).toContain('Act 11')
  })

  describe('the set playing now', () => {
    const tonight: EventDetail = {
      ...night,
      eventDate: '2026-10-09',
      startTime: '20:00',
      lineup: [
        { artist: { name: 'Opener' }, role: 'DJ', setStart: '2026-10-09T20:00:00+02:00' },
        { artist: { name: 'Peak' }, role: 'DJ', setStart: '2026-10-09T21:00:00+02:00' },
        { artist: { name: 'Closer' }, role: 'DJ', setStart: '2026-10-09T22:00:00+02:00' },
      ],
    }

    function row(view: VueWrapper, name: string) {
      return view.findAll('li').find((li) => li.text().includes(name))!
    }

    it('dots the set playing now, greys the ones over, and moves on with the clock', async () => {
      vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] })
      vi.setSystemTime(new Date('2026-10-09T21:30:00+02:00'))
      const view = await mountEvent(tonight)

      expect(row(view, 'Peak').text()).toContain('Playing now')
      expect(row(view, 'Opener').classes()).toContain('text-muted-foreground')
      expect(row(view, 'Closer').text()).not.toContain('Playing now')

      vi.advanceTimersByTime(60 * 60_000)
      await flushPromises()
      expect(row(view, 'Peak').text()).not.toContain('Playing now')
      expect(row(view, 'Closer').text()).toContain('Playing now')
    })

    it('keeps the last set live until the night ends', async () => {
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date('2026-10-09T23:30:00+02:00'))
      const view = await mountEvent(tonight)

      expect(row(view, 'Closer').text()).toContain('Playing now')
    })

    it('marks nothing on a cancelled night', async () => {
      vi.useFakeTimers({ toFake: ['Date'] })
      vi.setSystemTime(new Date('2026-10-09T21:30:00+02:00'))
      const view = await mountEvent({ ...tonight, status: 'CANCELLED' })

      expect(view.text()).not.toContain('Playing now')
      expect(row(view, 'Opener').classes()).not.toContain('text-muted-foreground')
    })
  })

  it('shows a lineup of ten in full, with no toggle', async () => {
    const lineup = Array.from({ length: 10 }, (_, index) => ({
      artist: { name: `Act ${index + 1}` },
      role: 'DJ' as const,
    }))
    const view = await mountEvent({ ...night, lineup })

    expect(view.text()).toContain('Act 10')
    expect(view.find('[data-testid="lineup-show-all"]').exists()).toBe(false)
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
    expect(section.text()).toContain("As the event's own text or the venue's page states it.")
  })

  it('shows the type and the genre tags under the date and venue line', async () => {
    const view = await mountEvent({
      ...night,
      eventType: 'CONCERT',
      genreTags: ['Soul', 'Afrobeats'],
    })

    const taxonomy = view.find('[data-testid="event-taxonomy"]')
    expect(taxonomy.text().replace(/\s+/g, ' ')).toBe('Concert · Soul · Afrobeats')
    expect(taxonomy.element.previousElementSibling?.textContent).toContain('2099')
  })

  it('shows the type alone when the event has no genre tags, in German', async () => {
    const view = await mountEvent({ ...night, eventType: 'PARTY', genreTags: [] }, 'de')

    expect(view.find('[data-testid="event-taxonomy"]').text()).toBe('Party')
  })

  it('shows the genre tags alone for an OTHER event, and no line when there is nothing', async () => {
    const tags = await mountEvent({ ...night, eventType: 'OTHER', genreTags: ['Jazz'] })
    expect(tags.find('[data-testid="event-taxonomy"]').text()).toBe('Jazz')
    tags.unmount()

    const bare = await mountEvent({ ...night, eventType: 'OTHER' })
    expect(bare.find('[data-testid="event-taxonomy"]').exists()).toBe(false)
  })

  it('marks a derived genre with where it comes from, and leaves a source genre plain', async () => {
    const view = await mountEvent({
      ...night,
      eventType: 'OTHER',
      genreTags: ['Jazz', 'Punk', 'Ska'],
      derivedGenreTags: { Punk: 'ARTIST', Ska: 'DESCRIPTION' },
    })

    const derived = view.findAll('[data-meta-derived]')
    expect(derived.map((d) => d.attributes('title'))).toEqual([
      "Genre from the acts' other events",
      'Genre from the event description',
    ])
    expect(derived[0]?.find('.sr-only').text()).toBe("(Genre from the acts' other events)")
    expect(view.find('[data-testid="event-taxonomy"]').text()).toContain('Jazz')
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

  describe('related events heading (#3337)', () => {
    const festsaal = { slug: 'festsaal-kreuzberg', name: 'Festsaal Kreuzberg' }
    const show: EventDetail = { ...night, venue: festsaal, genreTags: ['Indie'] }
    const sameVenue: EventSummary = { slug: 'next-show', title: 'Next Show', venue: festsaal }

    function heading(view: VueWrapper): string {
      return view.find('[data-testid="related-events"] h2').text()
    }

    for (const [locale, text] of [
      ['en', 'Also at Festsaal Kreuzberg'],
      ['de', 'Weitere Events bei Festsaal Kreuzberg'],
    ] as const) {
      it(`names the venue when it is the only match (${locale})`, async () => {
        const view = await mountEvent(show, locale, [
          sameVenue,
          { ...sameVenue, slug: 'other-show', genreTags: ['Techno'], artistNames: ['Someone'] },
        ])

        expect(heading(view)).toBe(text)
      })
    }

    const mixed: [string, EventSummary][] = [
      ['shares a genre tag', { ...sameVenue, slug: 'indie-night', genreTags: ['Indie'] }],
      ['shares an act', { ...sameVenue, slug: 'mock-night', artistNames: ['Mock Artist'] }],
      [
        'is at another venue',
        { ...sameVenue, slug: 'elsewhere', venue: { slug: 'lido', name: 'Lido' } },
      ],
    ]
    for (const [match, other] of mixed) {
      it(`says "More like this" when one suggestion ${match}`, async () => {
        const view = await mountEvent(show, 'en', [sameVenue, other])

        expect(heading(view)).toBe('More like this')
      })
    }
  })
})

describe('EventDetailView set-times call (#4631)', () => {
  const act = (slug: string, setStart?: string) => ({
    artist: { slug, name: slug },
    role: 'DJ' as const,
    ...(setStart ? { setStart } : {}),
  })
  const party: EventDetail = {
    ...night,
    eventType: 'PARTY',
    lineup: [act('rafon'), act('gnmr')],
  }
  const call = (view: VueWrapper) => view.find('[data-testid="set-times-call"]')

  it('asks an upcoming party of two untimed acts for its set times, by a mail naming the event', async () => {
    const view = await mountEvent(party)

    expect(call(view).text()).toContain('Know the set times?')
    const href = call(view).get('a').attributes('href') ?? ''
    expect(href.startsWith('mailto:hello@event-junkie.de?')).toBe(true)
    const params = new URLSearchParams(href.split('?')[1])
    expect(params.get('subject')).toBe('Set times: A Night')
    expect(params.get('body')).toContain('/en/events/a-night')
  })

  it.each([
    ['a concert', { ...party, eventType: 'CONCERT' as const }],
    ['a party of one act', { ...party, lineup: [act('rafon')] }],
    [
      'a party with a timed act',
      { ...party, lineup: [act('rafon', '2099-06-12T21:00:00Z'), act('gnmr')] },
    ],
    ['a past party', { ...party, eventDate: '2020-06-12' }],
    ['a cancelled party', { ...party, status: 'CANCELLED' as const }],
  ])('does not ask %s', async (_, event) => {
    const view = await mountEvent(event)

    expect(call(view).exists()).toBe(false)
  })
})
