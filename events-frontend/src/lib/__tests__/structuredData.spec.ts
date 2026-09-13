import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

import type { EventDetail, VenueDetail } from '@/api/types'
import {
  breadcrumbJsonLd,
  eventJsonLd,
  eventEndDate,
  eventStartDate,
  venueJsonLd,
  websiteJsonLd,
} from '@/lib/structuredData'
import { SITE_URL } from '@/lib/seo'

/**
 * Structured data fails in a way ordinary code does not: silently, and only in Google's index.
 * Nothing in the app renders it, no user sees it, and a missing required property means the rich
 * result simply never appears — with no error anywhere. These tests are the only feedback loop
 * short of the Rich Results Test.
 */

const event: EventDetail = {
  slug: '2026-06-12-lido-test-act',
  title: 'Test Act',
  eventType: 'CONCERT',
  status: 'SCHEDULED',
  eventDate: '2026-06-12',
  startTime: '20:00',
  doorsTime: '19:00',
  description: 'A night of something.',
  imageUrl: 'https://example.test/poster.jpg',
  ticketUrl: 'https://tickets.test/buy',
  pricePresale: 38,
  priceCurrency: 'EUR',
  soldOut: false,
  free: false,
  venue: { slug: 'lido', name: 'Lido', address: 'Cuvrystr. 7', city: 'Berlin' },
  lineup: [{ artist: { slug: 'test-act', name: 'Test Act' }, role: 'HEADLINER', billingOrder: 0 }],
  promoters: [{ slug: 'trinity', name: 'Trinity Music' }],
}

describe('eventStartDate', () => {
  it('carries the summer offset for a summer date', () => {
    expect(eventStartDate({ ...event, eventDate: '2026-06-12' })).toBe('2026-06-12T20:00:00+02:00')
  })

  it('carries the winter offset for a winter date', () => {
    // The bug a hardcoded offset would ship: every winter event an hour out, which reads as a typo
    // rather than as a fault, and is enough to miss the support act.
    expect(eventStartDate({ ...event, eventDate: '2026-01-12' })).toBe('2026-01-12T20:00:00+01:00')
  })

  it('falls back to doors when no start time is published', () => {
    expect(eventStartDate({ ...event, startTime: null })).toBe('2026-06-12T19:00:00+02:00')
  })

  it('emits a bare date rather than guessing a time', () => {
    // A wrong start time is the most damaging thing this module could publish; a date-only value
    // is valid ISO 8601 and Google accepts it.
    expect(eventStartDate({ ...event, startTime: null, doorsTime: null })).toBe('2026-06-12')
  })

  it('is undefined with no date at all', () => {
    expect(eventStartDate({ ...event, eventDate: undefined })).toBeUndefined()
  })
})

describe('eventEndDate', () => {
  it('is the stated end with its offset, the bare day for a run, and absent otherwise (ADR-029)', () => {
    expect(eventEndDate({ ...event, endDate: '2026-06-15', endTime: '10:00:00' })).toBe(
      '2026-06-15T10:00:00+02:00',
    )
    expect(eventEndDate({ ...event, endDate: '2026-07-31', endTime: null })).toBe('2026-07-31')
    expect(eventEndDate({ ...event, endDate: null })).toBeUndefined()
  })
})

describe('eventJsonLd', () => {
  // `offers` is dropped once an event is past, so the fixture needs a fixed clock.
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date('2026-06-01T12:00:00Z'))
  })

  afterEach(() => {
    vi.useRealTimers()
  })

  it("carries Google's three required properties", () => {
    const document = eventJsonLd(event, 'en')!

    expect(document.name).toBe('Test Act')
    expect(document.startDate).toBe('2026-06-12T20:00:00+02:00')
    expect(document.location).toMatchObject({
      '@type': 'Place',
      name: 'Lido',
      address: { streetAddress: 'Cuvrystr. 7', addressLocality: 'Berlin', addressCountry: 'DE' },
    })
  })

  // Partial structured data is not partially useful — it is rejected outright, and it costs a
  // crawl to discover that. Returning null keeps a broken document off the page entirely.
  const required: [string, EventDetail][] = [
    ['a title', { ...event, title: undefined }],
    ['a date', { ...event, eventDate: undefined }],
    ['a venue', { ...event, venue: undefined }],
    ['a venue name', { ...event, venue: { slug: 'lido' } }],
  ]

  for (const [missing, incomplete] of required) {
    it(`emits nothing without ${missing}`, () => {
      expect(eventJsonLd(incomplete, 'en')).toBeNull()
    })
  }

  it('picks the specific schema.org type where one is accurate', () => {
    expect(eventJsonLd({ ...event, eventType: 'CONCERT' }, 'en')!['@type']).toBe('MusicEvent')
    expect(eventJsonLd({ ...event, eventType: 'SCREENING' }, 'en')!['@type']).toBe('ScreeningEvent')
    // READING has no accurate subtype — TheaterEvent would assert a form we do not know.
    expect(eventJsonLd({ ...event, eventType: 'READING' }, 'en')!['@type']).toBe('Event')
  })

  it('maps the scheduling statuses it can express, and no others', () => {
    expect(eventJsonLd({ ...event, status: 'CANCELLED' }, 'en')!.eventStatus).toBe(
      'https://schema.org/EventCancelled',
    )
    expect(eventJsonLd({ ...event, status: 'POSTPONED' }, 'en')!.eventStatus).toBe(
      'https://schema.org/EventPostponed',
    )
    // RELOCATED has no schema.org counterpart. Undefined reads as EventScheduled, which is true —
    // a relocated event is still going ahead.
    expect(eventJsonLd({ ...event, status: 'RELOCATED' }, 'en')!.eventStatus).toBeUndefined()
  })

  it('reports sold out as availability rather than dropping the offer', () => {
    expect(eventJsonLd({ ...event, soldOut: true }, 'en')!.offers).toMatchObject({
      availability: 'https://schema.org/SoldOut',
      price: 38,
    })
  })

  it('prices a free event at zero', () => {
    expect(eventJsonLd({ ...event, free: true, pricePresale: null }, 'en')!.offers).toMatchObject({
      price: 0,
      availability: 'https://schema.org/InStock',
    })
  })

  it('omits offers entirely when no price is known', () => {
    // Inventing a price would contradict the imprint's "alle Angaben ohne Gewähr" in a format
    // built for machines to believe.
    const priceless = { ...event, pricePresale: null, priceBoxOffice: null, free: false }
    expect(eventJsonLd(priceless, 'en')!.offers).toBeUndefined()
  })

  it('sends buyers to the ticket seller, who holds the authoritative price', () => {
    expect(eventJsonLd(event, 'en')!.offers).toMatchObject({ url: 'https://tickets.test/buy' })
  })

  it('drops offers once the event is past, because the page drops the ticket link', () => {
    // Rule 1 of this file: never describe anything the page does not show, and a past event's
    // page shows no "Buy tickets".
    vi.setSystemTime(new Date('2026-06-13T12:00:00Z'))

    const document = eventJsonLd(event, 'en')!
    expect(document.offers).toBeUndefined()
    // The event is still real and still accurately dated — only the offer goes.
    expect(document.name).toBe('Test Act')
    expect(document.startDate).toBe('2026-06-12T20:00:00+02:00')
  })

  it('describes performers without claiming they are natural persons', () => {
    // §7.3 treats artist names as personal data because some artists are individuals. Of the two
    // types Google accepts for `performer`, only one asserts personhood — so use the other.
    expect(eventJsonLd(event, 'en')!.performer).toEqual([
      { '@type': 'PerformingGroup', name: 'Test Act' },
    ])
  })

  it('names promoters as the organizer', () => {
    expect(eventJsonLd(event, 'en')!.organizer).toEqual([
      { '@type': 'Organization', name: 'Trinity Music' },
    ])
  })

  it('points at the canonical URL of the active locale', () => {
    expect(eventJsonLd(event, 'de')!.url).toBe(`${SITE_URL}/de/events/${event.slug}`)
  })

  // A cached image is a path on our own origin (ADR-019), and Google fetches the `image` field
  // without a page to resolve it against.
  it('makes a cached image absolute', () => {
    const cached = { ...event, imageUrl: '/api/images/abc/768.jpg' }

    expect(eventJsonLd(cached, 'en')!.image).toBe(`${SITE_URL}/api/images/abc/768.jpg`)
  })
})

describe('venueJsonLd', () => {
  const venue: VenueDetail = {
    slug: 'lido',
    name: 'Lido',
    address: 'Cuvrystr. 7',
    postalCode: '10997',
    city: 'Berlin',
    latitude: 52.4977,
    longitude: 13.4437,
    websiteUrl: 'https://lido.test',
  }

  it('describes the venue as a MusicVenue with its full address', () => {
    expect(venueJsonLd(venue, 'en')).toMatchObject({
      '@type': 'MusicVenue',
      name: 'Lido',
      address: { streetAddress: 'Cuvrystr. 7', postalCode: '10997', addressLocality: 'Berlin' },
      geo: { '@type': 'GeoCoordinates', latitude: 52.4977, longitude: 13.4437 },
    })
  })

  it('omits coordinates rather than emitting a partial pair', () => {
    expect(venueJsonLd({ ...venue, longitude: null }, 'en')!.geo).toBeUndefined()
  })

  it('declares the language of the text it serves, not the locale of the page', () => {
    const described = {
      ...venue,
      description: 'A former cinema on the canal.',
      descriptionLanguage: 'en',
      descriptionAlt: 'Ein früheres Kino am Kanal.',
      descriptionAltLanguage: 'de',
    }
    expect(venueJsonLd(described, 'de')).toMatchObject({
      description: 'Ein früheres Kino am Kanal.',
      inLanguage: 'de',
    })
    expect(venueJsonLd(described, 'en')).toMatchObject({
      description: 'A former cinema on the canal.',
      inLanguage: 'en',
    })
  })

  it('shows the text it has when the venue has none in the visitor language', () => {
    const described = { ...venue, description: 'Only English here.', descriptionLanguage: 'en' }
    expect(venueJsonLd(described, 'de')).toMatchObject({
      description: 'Only English here.',
      inLanguage: 'en',
    })
  })
})

describe('breadcrumbJsonLd', () => {
  it('numbers the trail from one and leaves the current page unlinked', () => {
    const document = breadcrumbJsonLd(
      [
        ['Event Junkie', ''],
        ['Events', '/events'],
        ['Test Act', '/events/test'],
      ],
      'en',
    )

    expect(document.itemListElement).toEqual([
      { '@type': 'ListItem', position: 1, name: 'Event Junkie', item: `${SITE_URL}/en` },
      { '@type': 'ListItem', position: 2, name: 'Events', item: `${SITE_URL}/en/events` },
      // The last crumb is the page you are on — Google's guidance is to leave it without a link.
      { '@type': 'ListItem', position: 3, name: 'Test Act', item: undefined },
    ])
  })
})

// The description a page shows carries its own language, which is often not the page's. Claiming
// the locale for a German text on /en/ is a misrepresentation in a format built for machines.
describe('eventJsonLd inLanguage', () => {
  it('declares the language of the text it emitted', () => {
    const german = { ...event, description: 'Ein Abend mit Aussicht', descriptionLanguage: 'de' }

    expect(eventJsonLd(german, 'en')!.inLanguage).toBe('de')
    expect(eventJsonLd(german, 'de')!.inLanguage).toBe('de')
  })

  it('follows the other-language text when that is the one shown', () => {
    const both = {
      ...event,
      description: 'Ein Abend mit Aussicht',
      descriptionLanguage: 'de',
      descriptionAlt: 'An evening with a view',
      descriptionAltLanguage: 'en',
      descriptionAltOrigin: 'PUBLISHER',
    }

    expect(eventJsonLd(both, 'en')!.description).toBe('An evening with a view')
    expect(eventJsonLd(both, 'en')!.inLanguage).toBe('en')
  })

  it('claims no language for an unclassified description', () => {
    const unclassified = { ...event, description: 'Doors 19:30', descriptionLanguage: undefined }

    expect(eventJsonLd(unclassified, 'en')!.inLanguage).toBeUndefined()
  })
})

describe('websiteJsonLd', () => {
  it('claims a WebSite and not an Organization', () => {
    // The imprint states this is run by a private individual and not a company. An Organization
    // claim would contradict our own legal page, machine-readably.
    const document = websiteJsonLd('de')
    expect(document['@type']).toBe('WebSite')
    expect(JSON.stringify(document)).not.toContain('Organization')
    expect(document.url).toBe(`${SITE_URL}/de`)
  })
})
