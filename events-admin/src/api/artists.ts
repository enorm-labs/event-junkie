/**
 * The MusicBrainz review of the artists whose match is `AMBIGUOUS` (#3005). Hand-written:
 * `ArtistController.kt`, `MusicBrainzCandidateController.kt` and `EventController.kt` are the
 * source of truth.
 */

import { type AdminEvent, failOnError } from '@/api/events'

/** The fields of `ArtistResponse` the review reads. */
export interface ReviewArtist {
  id: number
  name: string
  slug: string
  musicbrainzMatch: string
}

export interface ReviewPage {
  content: ReviewArtist[]
  totalElements: number
  totalPages: number
}

/** The importer caps a page at 100. Twenty keeps the MusicBrainz look-ups of one page short. */
export const REVIEW_PAGE_SIZE = 20

/** The days ahead an artist must be billed to be reviewed: the two weeks `/musicbrainz-review` covers. */
export const REVIEW_DAYS = 14

/** `CandidateNameMatch`: which of the candidate's names equals the artist's. */
export type CandidateNameMatch = 'NAME' | 'OTHER_NAME' | 'NONE'

/** `MusicBrainzCandidateResponse`. A person's dates are always null (the privacy notice). */
export interface MusicBrainzCandidate {
  musicbrainzId: string
  name: string
  nameMatch: CandidateNameMatch
  type: string | null
  disambiguation: string | null
  country: string | null
  area: string | null
  founded: string | null
  dissolved: string | null
  url: string
}

export function reviewUrl(page: number): string {
  const params = new URLSearchParams({
    musicbrainzMatch: 'AMBIGUOUS',
    upcomingWithinDays: String(REVIEW_DAYS),
    page: String(page),
    size: String(REVIEW_PAGE_SIZE),
    sort: 'name,asc',
  })
  return `/api/admin/artists?${params}`
}

/** One page of the AMBIGUOUS artists billed in the next [REVIEW_DAYS] days, by name. */
export async function fetchReviewPage(
  page: number,
  fetchFn: typeof fetch = fetch,
): Promise<ReviewPage> {
  const response = await fetchFn(reviewUrl(page), { headers: { Accept: 'application/json' } })
  await failOnError(response, 'GET /api/admin/artists')
  return (await response.json()) as ReviewPage
}

/**
 * The candidates MusicBrainz returns for artist [id]'s name. The importer asks at MusicBrainz's
 * pace, so this can take seconds; a 503 throws with the importer's message.
 */
export async function fetchCandidates(
  id: number,
  fetchFn: typeof fetch = fetch,
): Promise<MusicBrainzCandidate[]> {
  const path = `/api/admin/artists/${id}/musicbrainz-candidates`
  const response = await fetchFn(path, { headers: { Accept: 'application/json' } })
  await failOnError(response, `GET ${path}`)
  return (await response.json()) as MusicBrainzCandidate[]
}

/** Stores [musicbrainzId] as artist [id]'s EXACT match. The importer changes no other field. */
export async function setMusicBrainzId(
  id: number,
  musicbrainzId: string,
  fetchFn: typeof fetch = fetch,
): Promise<ReviewArtist> {
  const path = `/api/admin/artists/${id}/musicbrainz-id`
  const response = await fetchFn(path, {
    method: 'PUT',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ musicbrainzId }),
  })
  await failOnError(response, `PUT ${path}`)
  return (await response.json()) as ReviewArtist
}

const berlinDay = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  timeZone: 'Europe/Berlin',
})

/** Today in Berlin, as `YYYY-MM-DD`: the day an event's `eventDate` is compared with. */
export function berlinToday(now: Date = new Date()): string {
  return berlinDay.format(now)
}

/**
 * The events that bill artist [id] from [today] on, soonest first. The list reads one page of
 * 100, newest first, so a long past does not push the coming events off it. An event over
 * midnight counts on the day it starts, as `eventDate` does.
 */
export async function fetchUpcomingEvents(
  id: number,
  today: string,
  fetchFn: typeof fetch = fetch,
): Promise<AdminEvent[]> {
  const params = new URLSearchParams({
    artistId: String(id),
    page: '0',
    size: '100',
    sort: 'eventDate,desc',
  })
  const response = await fetchFn(`/api/admin/events?${params}`, {
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, 'GET /api/admin/events')
  const { content } = (await response.json()) as { content: AdminEvent[] }
  return content
    .filter((event) => event.eventDate >= today)
    .sort((a, b) => a.eventDate.localeCompare(b.eventDate) || a.id - b.id)
}

/** "1976–1989", "1976–", "–1989", or null when MusicBrainz has neither date. */
export function lifeSpan({ founded, dissolved }: MusicBrainzCandidate): string | null {
  if (!founded && !dissolved) return null
  return `${founded ?? ''}–${dissolved ?? ''}`
}
