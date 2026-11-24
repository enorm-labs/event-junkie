import type { AdminEvent } from '@/api/events'

/** An event as the admin API answers it, with nothing optional set. */
export function adminEvent(overrides: Partial<AdminEvent> = {}): AdminEvent {
  return {
    id: 1,
    venueId: 3,
    title: 'Open Stage',
    subtitle: null,
    description: null,
    eventType: 'CONCERT',
    status: 'SCHEDULED',
    slug: '2026-10-14-open-stage',
    eventDate: '2026-10-14',
    doorsTime: null,
    startTime: '20:00:00',
    imageUrl: null,
    sourceUrl: null,
    sourceId: 'venue:1',
    ticketUrl: null,
    facebookEventUrl: null,
    genre: null,
    genreTags: [],
    pricePresale: null,
    priceBoxOffice: null,
    priceCurrency: 'EUR',
    priceNote: null,
    soldOut: false,
    free: false,
    artists: [],
    promoterIds: [],
    pinnedFields: [],
    ...overrides,
  }
}
