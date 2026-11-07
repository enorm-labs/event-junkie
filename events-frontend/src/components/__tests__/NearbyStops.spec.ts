import { afterEach, describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import type { NearbyStop } from '@/api/types'
import { setI18nLocale } from '@/i18n'
import NearbyStops from '@/components/NearbyStops.vue'

const stops: NearbyStop[] = [
  { name: 'Metzer Str.', lines: ['M2'], walkingMetres: 200 },
  { name: 'S+U Warschauer Str.', lines: ['S3', 'S5', 'U1'], walkingMetres: 1180 },
]

afterEach(() => setI18nLocale('en'))

describe('NearbyStops', () => {
  it('names each station with its lines and the walk, nearest first, under a label', () => {
    const list = mount(NearbyStops, { props: { stops } }).get('[data-testid="nearby-stops"]')

    expect(list.attributes('aria-label')).toBe('Nearby stations')
    expect(list.findAll('li').map((item) => item.text())).toEqual([
      'Metzer Str. · M2 · 200 m on foot',
      'S+U Warschauer Str. · S3, S5, U1 · 1,180 m on foot',
    ])
  })

  it('says it in German, with German digit grouping', () => {
    setI18nLocale('de')

    const list = mount(NearbyStops, { props: { stops } }).get('[data-testid="nearby-stops"]')

    expect(list.attributes('aria-label')).toBe('Haltestellen in der Nähe')
    expect(list.findAll('li')[1]?.text()).toBe('S+U Warschauer Str. · S3, S5, U1 · 1.180 m zu Fuß')
  })

  it('leaves the lines out when a station has none, rather than an empty separator', () => {
    const wrapper = mount(NearbyStops, {
      props: { stops: [{ name: 'Metzer Str.', lines: [], walkingMetres: 200 }] },
    })

    expect(wrapper.get('li').text()).toBe('Metzer Str. · 200 m on foot')
  })

  it.each([[[]], [undefined], [null]])('renders nothing for %s', (value) => {
    expect(
      mount(NearbyStops, { props: { stops: value } })
        .find('ul')
        .exists(),
    ).toBe(false)
  })
})
