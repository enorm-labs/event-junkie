import type { EventSummary, VenueSummary } from '@/api/types'

/** One marker on a `VenueMap`: a venue, where it is, and what the marker says about it. */
export interface MapPin {
  slug: string
  latitude: number
  longitude: number
  /** The marker's accessible name, which also is its tooltip. */
  label: string
  /** Short visible text inside the marker, such as an event count. */
  badge?: string
}

/** A venue together with the events it has in the requested range. */
export interface VenueEvents {
  venue: VenueSummary
  events: EventSummary[]
}

/** A venue's coordinate, or null when it has none: such a venue cannot be pinned. */
export function venuePosition(
  venue: VenueSummary | undefined,
): { latitude: number; longitude: number } | null {
  const latitude = venue?.latitude
  const longitude = venue?.longitude
  if (latitude == null || longitude == null) return null
  return { latitude, longitude }
}

/**
 * Groups events by venue, keeping only venues that can be pinned. The busiest venue comes first,
 * then by name, so the order is stable between two loads of the same data.
 */
export function groupByVenue(events: readonly EventSummary[]): VenueEvents[] {
  const groups = new Map<string, VenueEvents>()
  for (const event of events) {
    const venue = event.venue
    if (!venue?.slug || !venuePosition(venue)) continue
    const group = groups.get(venue.slug)
    if (group) group.events.push(event)
    else groups.set(venue.slug, { venue, events: [event] })
  }
  return [...groups.values()].sort(
    (a, b) =>
      b.events.length - a.events.length || (a.venue.name ?? '').localeCompare(b.venue.name ?? ''),
  )
}

/** A pin for a venue, or null when the venue has no coordinate. */
export function venuePin(
  venue: VenueSummary,
  label: string,
  badge?: string,
): MapPin | null {
  const position = venuePosition(venue)
  if (!venue.slug || !position) return null
  return { slug: venue.slug, ...position, label, badge }
}
