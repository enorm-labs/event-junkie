import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import CachedImage from '@/components/CachedImage.vue'
import type { ImageSource } from '@/api/types'

// Named rather than indexed, so an assertion can refer to one without `sources[0]` — which is
// `ImageSource | undefined` under `noUncheckedIndexedAccess`.
const avif: ImageSource = {
  type: 'image/avif',
  srcset: '/api/images/abc/192.avif 192w, /api/images/abc/288.avif 288w',
}
const webp: ImageSource = {
  type: 'image/webp',
  srcset: '/api/images/abc/192.webp 192w, /api/images/abc/288.webp 288w',
}
const jpeg: ImageSource = {
  type: 'image/jpeg',
  srcset: '/api/images/abc/192.jpg 192w, /api/images/abc/288.jpg 288w',
}
const sources: ImageSource[] = [avif, webp, jpeg]

const mount_ = (props: Partial<InstanceType<typeof CachedImage>['$props']> = {}) =>
  mount(CachedImage, {
    props: { src: '/api/images/abc/192.jpg', alt: 'A poster', sizes: '80px', ...props },
  })

describe('CachedImage', () => {
  // The uncached case is every environment that does not serve cached images yet, and every venue,
  // artist and promoter image on the ones that do (#833). It has to stay a plain <img>.
  it('renders a bare image when the API offered no formats', () => {
    const wrapper = mount_({ src: 'https://venue.test/poster.jpg' })

    expect(wrapper.findAll('source')).toHaveLength(0)
    expect(wrapper.get('img').attributes('src')).toBe('https://venue.test/poster.jpg')
  })

  // 328 of 3,251 seeded events have no image, and with serving on an image whose derivative is not
  // generated yet is reported absent too. Rendering nothing closes the layout up and reads as
  // broken rather than as sparse (#811).
  it('draws a placeholder when there is no image', () => {
    const wrapper = mount_({ src: null })

    expect(wrapper.find('img').exists()).toBe(false)
    expect(wrapper.find('picture').exists()).toBe(false)
    expect(wrapper.find('svg').exists()).toBe(true)
  })

  // The placeholder has to occupy the box the image would have, or it fixes nothing: the caller's
  // classes are what set that box.
  it('gives the placeholder the same classes the image would have had', () => {
    const wrapper = mount_({ src: undefined, imgClass: 'size-20 shrink-0' })

    expect(wrapper.get('div').classes()).toEqual(expect.arrayContaining(['size-20', 'shrink-0']))
  })

  it('offers each format the API returned, in the order it returned them', () => {
    // <picture> takes the first source the browser can decode, so the order is the preference and
    // sorting or grouping them would silently serve JPEG to a browser that reads AVIF.
    const wrapper = mount_({ sources })

    expect(wrapper.findAll('source').map((s) => s.attributes('type'))).toEqual([
      'image/avif',
      'image/webp',
      'image/jpeg',
    ])
    expect(wrapper.get('source').attributes('srcset')).toBe(avif.srcset)
  })

  it('puts sizes on every source, because srcset widths mean nothing without it', () => {
    const wrapper = mount_({ sources })

    expect(wrapper.findAll('source').every((s) => s.attributes('sizes') === '80px')).toBe(true)
  })

  it('keeps the caller classes on the image rather than on the picture', () => {
    // `contents` takes the <picture> out of the layout, so the <img> is still the flex item the
    // card's classes were written for. Moving them up would resize the wrapper and not the image.
    const wrapper = mount_({ sources, imgClass: 'size-20 shrink-0' })

    expect(wrapper.get('picture').classes()).toContain('contents')
    expect(wrapper.get('img').classes()).toEqual(['size-20', 'shrink-0'])
  })

  // `contents` promotes the <source>s into the caller's flex container too. Each one is a
  // zero-width flex item that still claims a `gap`. Unhidden, three formats push the image 48 px
  // right of a placeholder card's.
  it('keeps the sources out of the layout the picture was dissolved into', () => {
    const wrapper = mount_({ sources })

    expect(wrapper.findAll('source').every((s) => s.classes().includes('hidden'))).toBe(true)
  })

  // Without these a lazy image reserves no space and the page reflows as each one lands. `srcset`
  // is what makes it live: every render site sets `loading="lazy"` and offers several widths (#848).
  it('reserves the space with the intrinsic dimensions', () => {
    const wrapper = mount_({ sources, intrinsicWidth: 1200, intrinsicHeight: 630 })

    expect(wrapper.get('img').attributes('width')).toBe('1200')
    expect(wrapper.get('img').attributes('height')).toBe('630')
  })

  // 16% of the stored images have no dimensions: a stock JVM reads neither WebP nor AVIF, so
  // nothing measured them at import.
  it('omits both attributes when it has no dimensions', () => {
    const wrapper = mount_({ sources })

    expect(wrapper.get('img').attributes('width')).toBeUndefined()
    expect(wrapper.get('img').attributes('height')).toBeUndefined()
  })

  // One of the pair reserves nothing, so half an answer is the same layout shift as no answer —
  // reached less obviously. The API sends them together and this is the belt to that braces.
  it.each([
    ['width only', { intrinsicWidth: 1200, intrinsicHeight: null }],
    ['height only', { intrinsicWidth: null, intrinsicHeight: 630 }],
  ])('emits neither attribute given %s', (_label, dimensions) => {
    const wrapper = mount_({ sources, ...dimensions })

    expect(wrapper.get('img').attributes('width')).toBeUndefined()
    expect(wrapper.get('img').attributes('height')).toBeUndefined()
  })

  it('leaves the fallback image without a srcset of its own', () => {
    // The last source is JPEG and matches every browser, so this <img> is only reached by one with
    // no <picture> support at all — where a srcset would be equally unsupported.
    const wrapper = mount_({ sources })

    expect(wrapper.get('img').attributes('srcset')).toBeUndefined()
    expect(wrapper.get('img').attributes('loading')).toBe('lazy')
  })
})
