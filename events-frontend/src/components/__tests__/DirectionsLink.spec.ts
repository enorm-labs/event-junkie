import { afterEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import type { MapsPlatform } from '@/lib/directions'
import type { VenueSummary } from '@/api/types'

const platform = vi.hoisted(() => ({ value: 'web' as MapsPlatform }))

vi.mock('@/lib/directions', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/lib/directions')>()),
  clientMapsPlatform: () => platform.value,
}))

const { default: DirectionsLink } = await import('@/components/DirectionsLink.vue')

const kater: VenueSummary = {
  slug: 'kater',
  name: 'Kater Blau',
  latitude: 52.5118,
  longitude: 13.4253,
}

/** The platform's link replaces the web one after mount, so the render after it is the one to read. */
async function mountLink(venue: VenueSummary) {
  const wrapper = mount(DirectionsLink, { props: { venue } })
  await flushPromises()
  return wrapper
}

afterEach(() => {
  platform.value = 'web'
})

describe('DirectionsLink', () => {
  it("links a browser to OpenStreetMap's router in a new tab, and says it leaves the site", async () => {
    const link = (await mountLink(kater)).get('a')

    expect(link.attributes('href')).toBe(
      'https://www.openstreetmap.org/directions?to=52.5118,13.4253',
    )
    expect(link.attributes('target')).toBe('_blank')
    expect(link.attributes('rel')).toBe('noopener noreferrer')
    expect(link.text()).toBe('Directions (opens a maps app or site outside Event Junkie)')
  })

  it('hands an Android phone a geo: URI in the same tab', async () => {
    platform.value = 'android'
    const link = (await mountLink(kater)).get('a')

    expect(link.attributes('href')).toBe('geo:52.5118,13.4253?q=52.5118,13.4253(Kater%20Blau)')
    expect(link.attributes('target')).toBeUndefined()
  })

  it('opens Apple Maps on an iPhone or a Mac', async () => {
    platform.value = 'apple'
    const link = (await mountLink(kater)).get('a')

    expect(link.attributes('href')).toBe(
      'https://maps.apple.com/?daddr=52.5118,13.4253&q=Kater%20Blau',
    )
  })

  it('renders nothing for a venue without a coordinate', async () => {
    const wrapper = await mountLink({ ...kater, latitude: null, longitude: null })

    expect(wrapper.find('a').exists()).toBe(false)
  })
})
