/**
 * The importer's Instagram drafts (ADR-048). Hand-written: `InstagramDraftController.kt` and
 * `InstagramController.kt` are the source of truth.
 *
 * The model only drafts. A person creates the event through the New event form, then approves the
 * draft with the new event's id. Approve and reject judge the whole night: every pending draft of the
 * venue and date. A pending draft that already carries an `eventId` is a later post of a night
 * approved before: it proposes an update to that event.
 */
import {
  type AdminEvent,
  type EventRequest,
  failOnError,
  type NewEventForm,
  toEventRequest,
} from '@/api/events'

export type DraftStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'NOT_AN_EVENT'

export interface InstagramDraft {
  id: number
  venueId: number
  venueSlug: string
  venueName: string
  mediaId: string
  permalink: string
  postedAt: string
  /** Null once the draft is judged. */
  caption: string | null
  /** The text the model read off the post's images. Null when no image was read, and once judged. */
  flyerText: string | null
  eventDate: string | null
  /** `HH:mm:ss`, or null when the caption names no start. */
  startTime: string | null
  /** `HH:mm:ss`, or null when the post names no doors time. */
  doorsTime: string | null
  title: string | null
  acts: string[]
  pricePresale: number | null
  priceBoxOffice: number | null
  evidence: string | null
  confidence: number | null
  status: DraftStatus
  eventId: number | null
  createdAt: string
}

export interface DraftPage {
  content: InstagramDraft[]
  totalElements: number
  totalPages: number
}

/** The drafts of one venue and date: one night, announced by one post or several. */
export interface DraftNight {
  key: string
  venueName: string
  eventDate: string
  drafts: InstagramDraft[]
}

export interface InstagramVenue {
  slug: string
  name: string
  /** Null when the venue's Instagram URL names no profile. */
  handle: string | null
}

export const DRAFT_PAGE_SIZE = 50

const JSON_HEADERS = { Accept: 'application/json', 'Content-Type': 'application/json' }

export async function fetchDrafts(page: number, fetchFn: typeof fetch = fetch): Promise<DraftPage> {
  const query = new URLSearchParams({
    status: 'PENDING',
    page: String(page),
    size: String(DRAFT_PAGE_SIZE),
  })
  const response = await fetchFn(`/api/admin/instagram-drafts?${query}`, {
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, 'GET /api/admin/instagram-drafts')
  return (await response.json()) as DraftPage
}

export async function fetchDraft(
  id: number,
  fetchFn: typeof fetch = fetch,
): Promise<InstagramDraft> {
  const response = await fetchFn(`/api/admin/instagram-drafts/${id}`, {
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, `GET /api/admin/instagram-drafts/${id}`)
  return (await response.json()) as InstagramDraft
}

export async function approveDraft(
  id: number,
  eventId: number,
  fetchFn: typeof fetch = fetch,
): Promise<void> {
  const response = await fetchFn(`/api/admin/instagram-drafts/${id}/approve`, {
    method: 'POST',
    headers: JSON_HEADERS,
    body: JSON.stringify({ eventId }),
  })
  await failOnError(response, `POST /api/admin/instagram-drafts/${id}/approve`)
}

export async function rejectDraft(id: number, fetchFn: typeof fetch = fetch): Promise<void> {
  const response = await fetchFn(`/api/admin/instagram-drafts/${id}/reject`, {
    method: 'POST',
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, `POST /api/admin/instagram-drafts/${id}/reject`)
}

export async function fetchInstagramVenues(
  fetchFn: typeof fetch = fetch,
): Promise<InstagramVenue[]> {
  const response = await fetchFn('/api/admin/instagram/venues', {
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, 'GET /api/admin/instagram/venues')
  return (await response.json()) as InstagramVenue[]
}

/** Switches [slug] off and deletes its drafts, for a venue that objects (ADR-048 rule 8). */
export async function switchVenueOff(slug: string, fetchFn: typeof fetch = fetch): Promise<number> {
  const response = await fetchFn(`/api/admin/instagram/venues/${encodeURIComponent(slug)}`, {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, `DELETE /api/admin/instagram/venues/${slug}`)
  return ((await response.json()) as { draftsDeleted: number }).draftsDeleted
}

/** The event fields a later post can fill or correct. The lineup is edited in the event editor. */
const UPDATABLE = {
  doorsTime: 'Doors',
  startTime: 'Start',
  pricePresale: 'Presale',
  priceBoxOffice: 'Box office',
} as const

export type UpdatableField = keyof typeof UPDATABLE

export interface DraftChange {
  field: UpdatableField
  label: string
  /** The event's value as shown, or null when it has none. */
  from: string | null
  to: string
}

function shown(field: UpdatableField, value: string | number | null): string | null {
  if (value == null) return null
  return typeof value === 'number' ? value.toFixed(2) : value.slice(0, 5)
}

/**
 * What the drafts of a night approved before would change on [event]: each field a draft names
 * with another value. The newest post that names a field wins, as the drafts arrive newest first.
 */
export function draftChanges(event: AdminEvent, drafts: readonly InstagramDraft[]): DraftChange[] {
  return (Object.keys(UPDATABLE) as UpdatableField[]).flatMap((field) => {
    const named = drafts.map((d) => shown(field, d[field])).find((v) => v != null)
    const current = shown(field, event[field])
    return named == null || named === current
      ? []
      : [{ field, label: UPDATABLE[field], from: current, to: named }]
  })
}

/** The PUT body for [event] with [changes] applied. Every other field goes back as it was read. */
export function applyChanges(event: AdminEvent, changes: readonly DraftChange[]): EventRequest {
  const request = toEventRequest(event, { eventType: event.eventType, genre: event.genre ?? '' })
  for (const { field, to } of changes) {
    if (field === 'pricePresale' || field === 'priceBoxOffice') request[field] = Number(to)
    else request[field] = to
  }
  return request
}

/** The drafts of a page, grouped by venue and date in the order the importer sent them. */
export function groupByNight(drafts: readonly InstagramDraft[]): DraftNight[] {
  const nights = new Map<string, DraftNight>()
  for (const draft of drafts) {
    const key = `${draft.venueId}:${draft.eventDate}`
    const night = nights.get(key)
    if (night) night.drafts.push(draft)
    else
      nights.set(key, {
        key,
        venueName: draft.venueName,
        eventDate: draft.eventDate ?? '',
        drafts: [draft],
      })
  }
  return [...nights.values()]
}

/** The New event form, filled from [draft]. The source URL is the post, so the event page links it. */
export function formFromDraft(draft: InstagramDraft, empty: NewEventForm): NewEventForm {
  const price = (value: number | null) => (value == null ? '' : value.toFixed(2))
  return {
    ...empty,
    venueId: draft.venueId,
    title: draft.title ?? '',
    eventDate: draft.eventDate ?? '',
    startTime: draft.startTime?.slice(0, 5) ?? '',
    doorsTime: draft.doorsTime?.slice(0, 5) ?? '',
    pricePresale: price(draft.pricePresale),
    priceBoxOffice: price(draft.priceBoxOffice),
    sourceUrl: draft.permalink,
  }
}
