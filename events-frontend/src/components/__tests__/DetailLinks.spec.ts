import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import DetailLinks from '@/components/DetailLinks.vue'

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
})
