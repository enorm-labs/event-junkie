import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { defineComponent, h } from 'vue'
import type { VenueDetail } from '@/api/types'

const { getMock } = vi.hoisted(() => ({ getMock: vi.fn<(path: string) => Promise<unknown>>() }))

vi.mock('@/api/client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/client')>()),
  api: { GET: getMock },
  unwrap: (promise: Promise<unknown>) => promise,
}))

const { default: VenueDetailView } = await import('@/views/VenueDetailView.vue')

const Page = defineComponent({ render: () => h('p') })

const kater: VenueDetail = {
  slug: 'kater',
  name: 'Kater Blau',
  address: 'Holzmarktstr. 25',
  postalCode: '10243',
  city: 'Berlin',
  district: 'friedrichshain',
  latitude: 52.5118,
  longitude: 13.4253,
  websiteUrl: 'https://katerblau.de',
  venueTypes: ['club', 'open-air'],
  capacity: 1700,
}

let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  getMock.mockReset()
})

async function mountVenue(venue: VenueDetail) {
  getMock.mockImplementation((path: string) =>
    Promise.resolve(path === '/api/venues/{slug}' ? venue : { content: [] }),
  )
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/:locale(en|de)/venues/:slug', component: VenueDetailView },
      { path: '/:locale(en|de)/:rest(.*)', component: Page },
    ],
  })
  await router.push(`/en/venues/${venue.slug}`)
  wrapper = mount(VenueDetailView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('VenueDetailView', () => {
  it('opens the facts table with a Type row, one pill per type linking to the venues list', async () => {
    const view = await mountVenue(kater)

    const rows = view.findAll('[data-testid="venue-facets"] dl > div')
    expect(rows[0]?.find('dt').text()).toBe('Type')
    const pills = rows[0]?.findAll('dd a') ?? []
    expect(pills.map((pill) => pill.text())).toEqual(['Club', 'Open air'])
    expect(pills.map((pill) => pill.attributes('href'))).toEqual([
      '/en/venues?type=club',
      '/en/venues?type=open-air',
    ])
  })

  it('lists the capacity as the last row of the table, as plain text', async () => {
    const view = await mountVenue(kater)

    const rows = view.findAll('[data-testid="venue-facets"] dl > div')
    const last = rows[rows.length - 1]!
    expect(last.get('dt').text()).toBe('Capacity')
    expect(last.get('dd').text()).toBe('~1,700 people')
    expect(last.findAll('a')).toHaveLength(0)
  })

  it('shows the capacity row on a venue with no other facts', async () => {
    const view = await mountVenue({ ...kater, venueTypes: [] })

    expect(view.findAll('[data-testid="venue-facets"] dt').map((dt) => dt.text())).toEqual([
      'Capacity',
    ])
  })

  it('reads the address line as street, district and a link to the venue on the map', async () => {
    const view = await mountVenue(kater)

    const line = view.get('[data-testid="venue-map-link"]').element.parentElement
    expect(line?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Holzmarktstr. 25 · Friedrichshain · On the map · Directions (opens a maps app or site outside Event Junkie)',
    )
    expect(view.get('[data-testid="venue-map-link"]').attributes('href')).toBe(
      '/en/map?focus=kater',
    )
  })

  it('links the address line to a route in a maps app', async () => {
    const view = await mountVenue(kater)

    expect(view.get('[data-testid="venue-directions-link"]').attributes('href')).toBe(
      'https://www.openstreetmap.org/directions?to=52.5118,13.4253',
    )
  })

  it('drops the district from the address line when the venue has none', async () => {
    const view = await mountVenue({ ...kater, district: null })

    const line = view.get('[data-testid="venue-map-link"]').element.parentElement
    expect(line?.textContent?.replace(/\s+/g, ' ').trim()).toMatch(
      /^Holzmarktstr\. 25 · On the map · Directions/,
    )
  })

  it('offers no map link for a venue without a coordinate', async () => {
    const view = await mountVenue({ ...kater, latitude: null, longitude: null })

    expect(view.find('[data-testid="venue-map-link"]').exists()).toBe(false)
    expect(view.find('[data-testid="venue-directions-link"]').exists()).toBe(false)
    expect(view.text()).toContain('Holzmarktstr. 25 · Friedrichshain')
  })

  it('replaces the empty event list with one line and the programme link for a venue we do not import', async () => {
    const view = await mountVenue({
      ...kater,
      imported: false,
      programmeUrl: 'https://ra.co/clubs/185172',
    })
    const section = view.find('[data-testid="venue-not-imported"]')
    expect(section.text()).toContain("We don't list this venue's events yet.")
    const link = view.find('[data-testid="venue-programme-link"]')
    expect(link.attributes('href')).toBe('https://ra.co/clubs/185172')
    expect(link.text()).toBe('See the programme on Resident Advisor')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
    expect(view.text()).not.toContain('No upcoming nights here yet')
  })

  it('says only the line when such a venue has no programme online', async () => {
    const view = await mountVenue({ ...kater, imported: false, programmeUrl: null })
    expect(view.find('[data-testid="venue-not-imported"]').exists()).toBe(true)
    expect(view.find('[data-testid="venue-programme-link"]').exists()).toBe(false)
  })

  it('says a closed venue closed, and that no more nights come, whether or not we import it', async () => {
    const view = await mountVenue({ ...kater, imported: false, closedOn: '2020-08-30' })
    expect(view.find('[data-testid="venue-closure"]').text()).toBe('Closed for good in August 2020')
    expect(view.find('[data-testid="venue-closed"]').text()).toContain(
      'No more nights here: the venue has closed.',
    )
    expect(view.find('[data-testid="venue-not-imported"]').exists()).toBe(false)
  })

  it('names the last day of a venue that has announced its closure, and keeps its event list', async () => {
    const view = await mountVenue({ ...kater, imported: true, closedOn: '2099-10-31' })
    expect(view.find('[data-testid="venue-closure"]').text()).toBe(
      'Open until Sat, 31 Oct 2099, then closed for good',
    )
    expect(view.find('[data-testid="venue-closed"]').exists()).toBe(false)
    expect(view.text()).toContain('No upcoming nights here yet')
  })

  it('says nothing about a closure for an open venue', async () => {
    const view = await mountVenue({ ...kater, closedOn: null })
    expect(view.find('[data-testid="venue-closure"]').exists()).toBe(false)
  })

  it('keeps the event list for an imported venue', async () => {
    const view = await mountVenue({ ...kater, imported: true })
    expect(view.find('[data-testid="venue-not-imported"]').exists()).toBe(false)
    expect(view.text()).toContain('No upcoming nights here yet')
  })

  it('offers a prefilled mail that names the venue page (#2963)', async () => {
    const view = await mountVenue(kater)

    const mail = view.findAll('a').find((link) => link.text() === 'hello@event-junkie.de')
    const url = new URL(mail?.attributes('href') ?? '')
    expect(url.searchParams.get('subject')).toBe(`Wrong data: ${kater.name}`)
    expect(url.searchParams.get('body')).toContain('https://event-junkie.de/en/venues/kater')
  })

  it('tags the website link as a referral from this site (#2770)', async () => {
    const view = await mountVenue(kater)

    const website = view.findAll('a').find((link) => link.text() === 'Website')
    expect(website?.attributes('href')).toBe(
      'https://katerblau.de/?utm_source=event-junkie.de&utm_medium=referral',
    )
  })
})
