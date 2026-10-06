import { describe, expect, it } from 'vitest'

import { directionsUrl, mapsPlatform } from '@/lib/directions'

const IPHONE =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Mobile/15E148 Safari/604.1'
const IPAD_DESKTOP_MODE =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15'
const ANDROID =
  'Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36'
const WINDOWS =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36'
const LINUX = 'Mozilla/5.0 (X11; Linux x86_64; rv:131.0) Gecko/20100101 Firefox/131.0'

const KATER = { latitude: 52.5118, longitude: 13.4253 }

describe('mapsPlatform', () => {
  it.each([
    ['an iPhone', IPHONE, 'apple'],
    ['an iPad or a Mac', IPAD_DESKTOP_MODE, 'apple'],
    ['an Android phone', ANDROID, 'android'],
    ['Windows', WINDOWS, 'web'],
    ['Linux', LINUX, 'web'],
    ['no user agent', undefined, 'web'],
  ])('reads %s as %s', (_, userAgent, platform) => {
    expect(mapsPlatform(userAgent)).toBe(platform)
  })
})

describe('directionsUrl', () => {
  it('opens Apple Maps with the venue as the destination', () => {
    expect(directionsUrl(KATER, 'Kater Blau', 'apple')).toBe(
      'https://maps.apple.com/?daddr=52.5118,13.4253&q=Kater%20Blau',
    )
  })

  it('hands Android a geo: URI that the default maps app answers', () => {
    expect(directionsUrl(KATER, 'Kater Blau', 'android')).toBe(
      'geo:52.5118,13.4253?q=52.5118,13.4253(Kater%20Blau)',
    )
  })

  it("routes everyone else to OpenStreetMap's router", () => {
    expect(directionsUrl(KATER, 'Kater Blau', 'web')).toBe(
      'https://www.openstreetmap.org/directions?to=52.5118,13.4253',
    )
  })

  it('encodes the name, parentheses included, so it cannot end the geo: label early', () => {
    const name = 'Club & Bar (Hof) #2'
    expect(directionsUrl(KATER, name, 'android')).toBe(
      'geo:52.5118,13.4253?q=52.5118,13.4253(Club%20%26%20Bar%20%28Hof%29%20%232)',
    )
    expect(directionsUrl(KATER, name, 'apple')).toBe(
      'https://maps.apple.com/?daddr=52.5118,13.4253&q=Club%20%26%20Bar%20%28Hof%29%20%232',
    )
  })

  it('drops the label for a venue without a name', () => {
    expect(directionsUrl(KATER, null, 'apple')).toBe(
      'https://maps.apple.com/?daddr=52.5118,13.4253',
    )
    expect(directionsUrl(KATER, '', 'android')).toBe('geo:52.5118,13.4253?q=52.5118,13.4253')
  })
})
