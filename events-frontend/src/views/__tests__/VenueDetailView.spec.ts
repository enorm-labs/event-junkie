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

  it('keeps the capacity as plain text in the note under the table', async () => {
    const view = await mountVenue(kater)

    const facets = view.get('[data-testid="venue-facets"]')
    expect(facets.text()).toContain('~1,700 people')
    expect(facets.findAll('a').map((link) => link.text())).not.toContain('~1,700 people')
  })

  it('reads the address line as street, district and a link to the venue on the map', async () => {
    const view = await mountVenue(kater)

    const line = view.get('[data-testid="venue-map-link"]').element.parentElement
    expect(line?.textContent?.replace(/\s+/g, ' ').trim()).toBe(
      'Holzmarktstr. 25 · Friedrichshain · On the map',
    )
    expect(view.get('[data-testid="venue-map-link"]').attributes('href')).toBe(
      '/en/map?focus=kater',
    )
  })

  it('drops the district from the address line when the venue has none', async () => {
    const view = await mountVenue({ ...kater, district: null })

    const line = view.get('[data-testid="venue-map-link"]').element.parentElement
    expect(line?.textContent?.replace(/\s+/g, ' ').trim()).toBe('Holzmarktstr. 25 · On the map')
  })

  it('offers no map link for a venue without a coordinate', async () => {
    const view = await mountVenue({ ...kater, latitude: null, longitude: null })

    expect(view.find('[data-testid="venue-map-link"]').exists()).toBe(false)
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

  it('keeps the event list for an imported venue', async () => {
    const view = await mountVenue({ ...kater, imported: true })
    expect(view.find('[data-testid="venue-not-imported"]').exists()).toBe(false)
    expect(view.text()).toContain('No upcoming nights here yet')
  })
})
