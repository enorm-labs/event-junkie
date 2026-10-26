import { mount } from '@vue/test-utils'
import { defineComponent, h, shallowRef } from 'vue'
import { afterEach, describe, expect, it } from 'vitest'

import type { GenreTag, VenueListItem } from '@/api/types'
import { useFilterLabels } from '@/composables/useFilterLabels'
import type { FilterLists } from '@/composables/useFilterLists'
import { i18n } from '@/i18n'

const TAGS = [
  { slug: 'free-jazz', name: 'Free Jazz', family: 'jazz-blues' },
  { slug: 'bebop', name: 'Bebop', family: 'jazz-blues' },
] as GenreTag[]
const VENUES = [{ slug: 'lido', name: 'Lido' }] as VenueListItem[]

function labels(loaded = true): ReturnType<typeof useFilterLabels>['filterLabels'] {
  const lists = {
    genres: { data: shallowRef(loaded ? TAGS : null) },
    venues: { data: shallowRef(loaded ? VENUES : null) },
  } as unknown as FilterLists
  let filterLabels!: ReturnType<typeof useFilterLabels>['filterLabels']
  mount(
    defineComponent({
      setup() {
        filterLabels = useFilterLabels(lists).filterLabels
        return () => h('div')
      },
    }),
  )
  return filterLabels
}

describe('filterLabels', () => {
  afterEach(() => {
    i18n.global.locale.value = 'en'
  })

  it('names nothing when no filter is set', () => {
    expect(labels()({})).toEqual([])
  })

  it('names every kind of filter, in the order the issue sets', () => {
    expect(
      labels()({
        q: 'Sun Ra',
        free: true,
        district: ['neukoelln', 'kreuzberg'],
        venueType: ['club'],
        venue: ['lido'],
        genre: ['free-jazz'],
        family: ['jazz-blues'],
        timeOfDay: ['evening'],
        eventType: ['CONCERT', 'PARTY'],
      }),
    ).toEqual([
      'Concert',
      'Party',
      'Evening',
      'Jazz & Blues',
      'Free Jazz',
      'Lido',
      'Club',
      'Neukölln',
      'Kreuzberg',
      'Free',
      '“Sun Ra”',
    ])
  })

  it('names the spoken language after the type', () => {
    expect(labels()({ eventType: ['COMEDY'], language: ['en'] })).toEqual(['Comedy', 'English'])
  })

  it('names the event features', () => {
    expect(labels()({ feature: ['flinta-only', 'open-end'] })).toEqual(['FLINTA* only', 'Open end'])
  })

  it('leaves prices and sold-out out of the name', () => {
    expect(labels()({ minPrice: 5, maxPrice: 20, excludeSoldOut: true })).toEqual([])
  })

  it('names the family of a style when the link carries only the style, as the bar shows it', () => {
    expect(labels()({ genre: ['free-jazz'] })).toEqual(['Jazz & Blues', 'Free Jazz'])
  })

  it('names every chosen style after their one family', () => {
    expect(labels()({ genre: ['free-jazz', 'bebop'] })).toEqual([
      'Jazz & Blues',
      'Free Jazz',
      'Bebop',
    ])
  })

  it('shows the slug of a venue or a style until the lists load', () => {
    expect(labels(false)({ venue: ['lido'], genre: ['free-jazz'] })).toEqual(['free-jazz', 'lido'])
  })

  it('names every chosen venue, and an unknown one by its slug', () => {
    expect(labels()({ venue: ['lido', 'gone'] })).toEqual(['Lido', 'gone'])
  })

  it('says it in German', () => {
    i18n.global.locale.value = 'de'
    expect(
      labels()({ eventType: ['CONCERT'], timeOfDay: ['late'], free: true, q: 'Sun Ra' }),
    ).toEqual(['Konzert', 'Nachts', 'Kostenlos', '„Sun Ra“'])
  })
})
