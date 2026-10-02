import type { EventSummary, VenueSummary } from '@/api/types'
import { distanceKm, type Position } from '@/lib/geo'

/** One marker on a `VenueMap`: a venue, where it is, and what the marker says about it. */
export interface MapPin {
  slug: string
  latitude: number
  longitude: number
  /** The marker's accessible name, which also is its tooltip. */
  label: string
  /** The venue's name, shown beside the marker while it is selected. */
  name?: string
  /**
   * Short visible text inside the marker, such as an event count. An empty string keeps the
   * badge's size without text; no badge at all makes the marker a dot.
   */
  badge?: string
  /** Something is on there now: the marker pulses. The label must say so too. */
  live?: boolean
  /** Outside the chosen radius: drawn faintly rather than removed, so the map keeps its context. */
  dimmed?: boolean
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
  extra: Pick<MapPin, 'live' | 'dimmed'> = {},
): MapPin | null {
  const position = venuePosition(venue)
  if (!venue.slug || !position) return null
  return { slug: venue.slug, ...position, name: venue.name ?? undefined, label, badge, ...extra }
}

/** The radii "near" offers, in kilometres: a walk, a short ride, across a district. */
export const RADII = [1, 2, 5] as const
export const DEFAULT_RADIUS = 2

/** The `radius` query value when it is one of `RADII`, otherwise the default. */
export function radiusFromQuery(value: string): number {
  const radius = Number(value)
  return (RADII as readonly number[]).includes(radius) ? radius : DEFAULT_RADIUS
}

/**
 * The items within `radiusKm` of `origin`, nearest first. An item whose venue has no coordinate
 * drops out; the view counts those itself.
 */
export function nearby<T extends { venue: VenueSummary }>(
  items: readonly T[],
  origin: Position,
  radiusKm: number,
): (T & { distanceKm: number })[] {
  return items
    .flatMap((item) => {
      const position = venuePosition(item.venue)
      if (!position) return []
      const distance = distanceKm(origin, position)
      return distance <= radiusKm ? [{ ...item, distanceKm: distance }] : []
    })
    .sort((a, b) => a.distanceKm - b.distanceKm)
}
