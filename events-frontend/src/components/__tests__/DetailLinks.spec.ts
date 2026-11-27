import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DetailLinks from '@/components/DetailLinks.vue'
import { serviceIcons } from '@/lib/serviceIcons'

describe('DetailLinks', () => {
  it('renders nothing without links', () => {
    expect(
      mount(DetailLinks, { props: { links: [] } })
        .find('p')
        .exists(),
    ).toBe(false)
  })

  it('separates the links with a hidden dot and opens them in a new tab', () => {
    const view = mount(DetailLinks, {
      props: {
        links: [
          { label: 'Website', url: 'https://example.com/' },
          { label: 'Bandcamp', url: 'https://example.bandcamp.com/' },
        ],
      },
    })

    const anchors = view.findAll('a')
    expect(anchors.map((a) => a.text())).toEqual(['Website', 'Bandcamp'])
    expect(anchors[1]?.attributes('rel')).toBe('noopener noreferrer')
    expect(view.findAll('[aria-hidden="true"]')).toHaveLength(1)
  })

  it('puts a hidden 21 px (size-5.25) icon before the label when the link names one', () => {
    const view = mount(DetailLinks, {
      props: {
        links: [
          {
            label: 'Spotify',
            url: 'https://open.spotify.com/artist/x',
            icon: serviceIcons.spotify,
          },
          { label: 'Website', url: 'https://example.com/' },
        ],
      },
    })

    const [spotify, website] = view.findAll('a')
    const icon = spotify?.find('svg')
    expect(icon?.attributes('aria-hidden')).toBe('true')
    expect(icon?.classes()).toEqual(expect.arrayContaining(['size-5.25', 'text-foreground']))
    expect(spotify?.text()).toBe('Spotify')
    expect(website?.find('svg').exists()).toBe(false)
  })

  it('drops the dots for a gap when the links carry icons, the lead included', () => {
    const view = mount(DetailLinks, {
      props: {
        lead: 'Collective',
        links: [
          { label: 'Website', url: 'https://example.de/', icon: serviceIcons.website },
          { label: 'Instagram', url: 'https://instagram.com/x', icon: serviceIcons.instagram },
        ],
      },
    })

    expect(view.find('p').classes()).toEqual(expect.arrayContaining(['flex', 'gap-x-4']))
    expect(view.text()).not.toContain('·')
    expect(view.text()).toContain('Collective')
  })
})
