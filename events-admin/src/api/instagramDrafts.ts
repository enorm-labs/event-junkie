/**
 * The importer's Instagram drafts (ADR-048). Hand-written: `InstagramDraftController.kt` and
 * `InstagramController.kt` are the source of truth.
 *
 * The model only drafts. A person creates the event through the New event form, then approves the
 * draft with the new event's id. Approve and reject judge the whole night: every pending draft of the
 * venue and date.
 */
import { failOnError, type NewEventForm } from '@/api/events'

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
  eventDate: string | null
  /** `HH:mm:ss`, or null when the caption names no start. */
  startTime: string | null
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
    pricePresale: price(draft.pricePresale),
    priceBoxOffice: price(draft.priceBoxOffice),
    sourceUrl: draft.permalink,
  }
}
