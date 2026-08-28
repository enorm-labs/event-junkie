import { describe, expect, it } from 'vitest'

import type { ArtistDetail, EventDetail, VenueDetail } from '@/api/types'
import {
  APP_NAME,
  artistPageMeta,
  eventPageMeta,
  formatTitle,
  HOME_TITLE,
  placeholderPageMeta,
  promoterPageMeta,
  staticPageMeta,
  venuePageMeta,
} from '@/lib/pageMeta'

/**
 * These values will eventually be produced twice — here for the client, and again server-side by
 * the meta injector (ADR-014 §Decision 3). The tests therefore pin the *output*, not just the
 * absence of crashes: when the injector is built, this file is the specification it has to match.
 */

const event: EventDetail = {
  slug: '2026-06-12-lido-test-act',
  title: 'Test Act',
  eventDate: '2026-06-12',
  startTime: '20:00',
  imageUrl: 'https://example.test/poster.jpg',
  venue: { slug: 'lido', name: 'Lido', city: 'Berlin' },
}

describe('eventPageMeta', () => {
  it('leads the description with when and where', () => {
    // The facts come first deliberately: someone deciding whether to open a link in a group chat
    // wants the date and the room before they want promotional copy.
    expect(eventPageMeta(event, 'en').description).toBe('Fri, 12 Jun 2026 · Lido, Berlin')
  })

  it('formats that date in the active locale', () => {
    expect(eventPageMeta(event, 'de').description).toBe('Fr., 12. Juni 2026 · Lido, Berlin')
  })

  it("appends the venue's own blurb after the facts", () => {
    const withBlurb = { ...event, description: 'A night of something.' }
    expect(eventPageMeta(withBlurb, 'en').description).toBe(
      'Fri, 12 Jun 2026 · Lido, Berlin — A night of something.',
    )
  })

  it('drops the parts it does not have instead of leaving empty separators', () => {
    // `· ` or ` — ` with nothing on one side reads as a rendering bug in a preview card.
    const bare = { ...event, eventDate: undefined, venue: undefined, description: 'Just this.' }
    expect(eventPageMeta(bare, 'en').description).toBe('Just this.')
  })

  it('has no description at all when there is nothing true to say', () => {
    const empty = { ...event, eventDate: undefined, venue: undefined }
    expect(eventPageMeta(empty, 'en').description).toBeUndefined()
  })

  it('titles the page with the event name and the brand', () => {
    expect(eventPageMeta(event, 'en').title).toBe(`Test Act · ${APP_NAME}`)
  })

  it('carries the poster as the preview image', () => {
    expect(eventPageMeta(event, 'en').image).toBe('https://example.test/poster.jpg')
    expect(eventPageMeta({ ...event, imageUrl: null }, 'en').image).toBeUndefined()
  })

  // A cached image comes back as a path on our own origin (ADR-019). `og:image` is read by a
  // crawler with no page to resolve it against, so a path there is a preview that never loads.
  it('makes a cached image absolute', () => {
    const cached = { ...event, imageUrl: '/api/images/abc/768.jpg' }

    expect(eventPageMeta(cached, 'en').image).toBe('https://event-junkie.de/api/images/abc/768.jpg')
  })
})

describe('description length', () => {
  it('truncates on a word boundary rather than mid-word', () => {
    const long = { ...event, description: 'word '.repeat(80) }
    const description = eventPageMeta(long, 'en').description!

    expect(description.length).toBeLessThanOrEqual(200)
    expect(description).toMatch(/word…$/)
  })

  it('collapses the whitespace scraped copy arrives with', () => {
    // Venue blurbs come from HTML, so newlines and runs of spaces are the norm, and they render
    // literally in a preview card.
    const messy = { ...event, description: 'Two\n\n  lines.' }
    expect(eventPageMeta(messy, 'en').description).toContain('Two lines.')
  })

  it('cuts mid-token when there is no usable word boundary', () => {
    // A 200-character "word" is a URL or a hashtag wall; keeping only the first few characters
    // before it would waste the whole budget.
    const wall = { ...event, description: 'x'.repeat(400) }
    const description = eventPageMeta(wall, 'en').description!

    expect(description.length).toBeLessThanOrEqual(200)
    expect(description.endsWith('…')).toBe(true)
  })
})

describe('venuePageMeta', () => {
  const venue: VenueDetail = {
    slug: 'lido',
    name: 'Lido',
    address: 'Cuvrystr. 7',
    postalCode: '10997',
    city: 'Berlin',
    imageUrl: 'https://example.test/lido.jpg',
  }

  it('falls back to the address when the venue has no description', () => {
    expect(venuePageMeta(venue).description).toBe('Cuvrystr. 7, 10997 Berlin')
  })

  it('puts its own description first and keeps the address after it', () => {
    const described = { ...venue, description: 'A room by the canal.' }
    expect(venuePageMeta(described).description).toBe(
      'A room by the canal. — Cuvrystr. 7, 10997 Berlin',
    )
  })

  it('omits the description when there is neither', () => {
    expect(venuePageMeta({ slug: 'x', name: 'X' }).description).toBeUndefined()
  })
})

describe('artistPageMeta and promoterPageMeta', () => {
  it('describes an artist we know something about', () => {
    const artist: ArtistDetail = { slug: 'a', name: 'A', description: 'Berlin duo.' }
    expect(artistPageMeta(artist).description).toBe('Berlin duo.')
  })

  it('says nothing rather than padding a name into a sentence', () => {
    // Same rule as the structured data: omit rather than invent. The site-level description is a
    // better answer than a generated one that says nothing.
    expect(artistPageMeta({ slug: 'a', name: 'A' }).description).toBeUndefined()
    expect(promoterPageMeta({ slug: 'p', name: 'P' }).description).toBeUndefined()
  })
})

describe('titles', () => {
  it('falls back to the home title when there is no page name', () => {
    expect(formatTitle(null)).toBe(HOME_TITLE)
    expect(staticPageMeta(null).title).toBe(HOME_TITLE)
  })

  it('suffixes interior pages with the brand', () => {
    expect(staticPageMeta('Venues').title).toBe(`Venues · ${APP_NAME}`)
    expect(placeholderPageMeta('Event not found').title).toBe(`Event not found · ${APP_NAME}`)
  })
})
