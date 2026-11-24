import { describe, expect, it } from 'vitest'

import { mount } from '@vue/test-utils'
import ListenLinks from '@/components/ListenLinks.vue'

/**
 * The line-up's listen links (#2723). Plain anchors only: an embedded player would send the
 * visitor's IP to the service before any click, which the privacy notice does not cover.
 */
describe('ListenLinks', () => {
  const artist = {
    slug: 'mock-artist',
    name: 'Mock Artist',
    bandcampUrl: 'https://mock-artist.bandcamp.com/',
    soundcloudUrl: 'https://soundcloud.com/mock-artist',
  }

  it('links each service the artist has, named for the act', () => {
    const wrapper = mount(ListenLinks, { props: { artist } })
    const links = wrapper.findAll('a')

    expect(links.map((a) => a.text())).toEqual(['Bandcamp', 'SoundCloud'])
    expect(links.map((a) => a.attributes('href'))).toEqual([
      'https://mock-artist.bandcamp.com/',
      'https://soundcloud.com/mock-artist',
    ])
    expect(links[0]!.attributes('aria-label')).toBe('Listen to Mock Artist on Bandcamp')
    expect(links[0]!.attributes('rel')).toBe('noopener noreferrer')
  })

  const everywhere = {
    ...artist,
    youtubeUrl: 'https://www.youtube.com/@mockartist',
    spotifyUrl: 'https://open.spotify.com/artist/mock',
  }
  const without = (...keys: (keyof typeof everywhere)[]) =>
    Object.fromEntries(
      Object.entries(everywhere).filter(([key]) => !keys.includes(key as keyof typeof everywhere)),
    )

  it.each([
    ['a DJ', 'DJ', everywhere, ['SoundCloud', 'Bandcamp']],
    ['a DJ without SoundCloud', 'DJ', without('soundcloudUrl'), ['Bandcamp', 'YouTube']],
    ['a headliner', 'HEADLINER', everywhere, ['Bandcamp', 'YouTube']],
    ['an act with no role', undefined, everywhere, ['Bandcamp', 'YouTube']],
    ['a live act without Bandcamp', 'LIVE', without('bandcampUrl'), ['YouTube', 'Spotify']],
    [
      'a band on Spotify and SoundCloud',
      'SUPPORT',
      without('bandcampUrl', 'youtubeUrl'),
      ['Spotify', 'SoundCloud'],
    ],
  ])('offers %s the first two services of its order', (_, role, act, expected) => {
    const wrapper = mount(ListenLinks, { props: { artist: act, role } })

    expect(wrapper.findAll('a').map((a) => a.text())).toEqual(expected)
  })

  it('embeds nothing', () => {
    const wrapper = mount(ListenLinks, { props: { artist } })

    expect(wrapper.find('iframe, script, img, audio, video').exists()).toBe(false)
  })

  it('renders nothing for an act with no link', () => {
    const wrapper = mount(ListenLinks, { props: { artist: { slug: 'quiet', name: 'Quiet' } } })

    expect(wrapper.find('a').exists()).toBe(false)
  })
})
