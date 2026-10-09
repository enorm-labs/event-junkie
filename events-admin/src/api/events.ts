/**
 * The admin event endpoints. Hand-written: `EventRequests.kt` and `EventResponses.kt` are the
 * source of truth.
 *
 * `PUT /api/admin/events/{id}` replaces the whole event and pins every field whose value changes
 * (ADR-042). So an edit sends back every stored value as it was read, and only the edited field
 * differs: a field left out of the body would reset to its default and pin that.
 */

/** `EventType` in `events-core`, in its order. */
export const EVENT_TYPES = [
  'CONCERT',
  'FESTIVAL',
  'PARTY',
  'QUIZ',
  'SHOW',
  'COMEDY',
  'SCREENING',
  'EXHIBITION',
  'READING',
  'OTHER',
] as const

export type EventType = (typeof EVENT_TYPES)[number]

/** `ArtistRole` in `events-core`, in its order. */
export const ARTIST_ROLES = ['HEADLINER', 'SUPPORT', 'DJ', 'LIVE'] as const

export type ArtistRole = (typeof ARTIST_ROLES)[number]

export interface EventArtist {
  artistId: number
  role: ArtistRole
  billingOrder: number
  stage: string | null
}

/** The fields of `EventResponse` an edit reads back. Prices arrive as JSON numbers. */
export interface AdminEvent {
  id: number
  venueId: number
  title: string
  subtitle: string | null
  description: string | null
  eventType: EventType
  status: string
  slug: string
  eventDate: string
  doorsTime: string | null
  startTime: string | null
  imageUrl: string | null
  sourceUrl: string | null
  sourceId: string
  ticketUrl: string | null
  facebookEventUrl: string | null
  genre: string | null
  genreTags: string[]
  pricePresale: number | null
  priceBoxOffice: number | null
  priceCurrency: string
  priceNote: string | null
  soldOut: boolean
  free: boolean
  artists: EventArtist[]
  promoterIds: number[]
  pinnedFields: string[]
}

/** `EventRequest`: the body of a PUT. */
export interface EventRequest {
  venueId: number
  title: string
  subtitle: string | null
  description: string | null
  eventType: EventType
  status: string
  eventDate: string
  doorsTime: string | null
  startTime: string | null
  imageUrl: string | null
  sourceUrl: string | null
  sourceId: string
  ticketUrl: string | null
  facebookEventUrl: string | null
  genre: string | null
  pricePresale: number | null
  priceBoxOffice: number | null
  priceCurrency: string
  priceNote: string | null
  soldOut: boolean
  free: boolean
  artists: EventArtist[]
  promoterIds: number[]
}

/** The fields this app edits. Every other field goes back as it was read. */
export interface EventEdit {
  eventType: EventType
  /** Blank clears the genre. */
  genre: string
  /** The lineup to save. Left out, the stored lineup goes back. */
  artists?: EventArtist[]
  /** The promoters to save. Left out, the stored promoters go back. */
  promoterIds?: number[]
}

/**
 * The full PUT body for [event] with [edit] applied. The lineup goes back with the four values
 * the importer compares, so an unchanged lineup pins nothing; a changed one pins `lineup`, and
 * changed promoters pin `promoters` (ADR-042).
 */
export function toEventRequest(event: AdminEvent, edit: EventEdit): EventRequest {
  const genre = edit.genre.trim()
  return {
    venueId: event.venueId,
    title: event.title,
    subtitle: event.subtitle,
    description: event.description,
    eventType: edit.eventType,
    status: event.status,
    eventDate: event.eventDate,
    doorsTime: event.doorsTime,
    startTime: event.startTime,
    imageUrl: event.imageUrl,
    sourceUrl: event.sourceUrl,
    sourceId: event.sourceId,
    ticketUrl: event.ticketUrl,
    facebookEventUrl: event.facebookEventUrl,
    genre: genre === '' ? null : genre,
    pricePresale: event.pricePresale,
    priceBoxOffice: event.priceBoxOffice,
    priceCurrency: event.priceCurrency,
    priceNote: event.priceNote,
    soldOut: event.soldOut,
    free: event.free,
    artists: (edit.artists ?? event.artists).map(({ artistId, role, billingOrder, stage }) => ({
      artistId,
      role,
      billingOrder,
      stage,
    })),
    promoterIds: edit.promoterIds ?? event.promoterIds,
  }
}

async function failOnError(response: Response, what: string): Promise<Response> {
  if (response.ok) return response
  // The importer answers a refused request with an RFC 9457 Problem Detail.
  const detail = await response
    .json()
    .then((body: { detail?: unknown }) => (typeof body.detail === 'string' ? body.detail : null))
    .catch(() => null)
  throw new Error(`${what}: HTTP ${response.status}${detail ? ` — ${detail}` : ''}`)
}

export async function fetchEvent(id: number, fetchFn: typeof fetch = fetch): Promise<AdminEvent> {
  const response = await fetchFn(`/api/admin/events/${id}`, {
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, `GET /api/admin/events/${id}`)
  return (await response.json()) as AdminEvent
}

/** Replaces event [id] with [request]. The answer carries the pins the edit set. */
export async function updateEvent(
  id: number,
  request: EventRequest,
  fetchFn: typeof fetch = fetch,
): Promise<AdminEvent> {
  const response = await fetchFn(`/api/admin/events/${id}`, {
    method: 'PUT',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  await failOnError(response, `PUT /api/admin/events/${id}`)
  return (await response.json()) as AdminEvent
}

/** Removes the pin on [field], so the next import writes the source's value again. */
export async function unpinField(
  id: number,
  field: string,
  fetchFn: typeof fetch = fetch,
): Promise<void> {
  const path = `/api/admin/events/${id}/pins/${encodeURIComponent(field)}`
  const response = await fetchFn(path, { method: 'DELETE' })
  await failOnError(response, `DELETE ${path}`)
}
