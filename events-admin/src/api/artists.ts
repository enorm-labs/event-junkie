/**
 * The MusicBrainz review of the artists whose match is `AMBIGUOUS` (#3005). Hand-written:
 * `ArtistController.kt`, `MusicBrainzCandidateController.kt` and `EventController.kt` are the
 * source of truth.
 */

import { type AdminEvent } from '@/api/events'
import { failOnError, type Page } from '@/api/http'

/** The fields of `ArtistResponse` the review reads. */
export interface ReviewArtist {
  id: number
  name: string
  slug: string
  musicbrainzMatch: string
  /** The MBID of an EXACT match; null otherwise. */
  musicbrainzId?: string | null
  /** How many visitors wait for an act of this name (#5214); only the watched list sends it. */
  watchers?: number
}

export type ReviewPage = Page<ReviewArtist>

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
  /** How many visitors wait for this MBID (#5214); 0 when nobody does. */
  watchers: number
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
 * The AMBIGUOUS artists whose name visitors wait for (#5214), the most watched first, whatever their dates: a watch turns
 * into a follow only once the row is EXACT.
 */
export async function fetchWatchedArtists(fetchFn: typeof fetch = fetch): Promise<ReviewArtist[]> {
  const path = '/api/admin/artists/watched'
  const response = await fetchFn(path, { headers: { Accept: 'application/json' } })
  await failOnError(response, `GET ${path}`)
  return (await response.json()) as ReviewArtist[]
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

/** How many matched artists a name search lists. A search narrows; it never pages. */
export const MATCHED_SEARCH_SIZE = 20

export function matchedSearchUrl(term: string): string {
  const params = new URLSearchParams({
    name: term,
    musicbrainzMatch: 'EXACT',
    page: '0',
    size: String(MATCHED_SEARCH_SIZE),
    sort: 'name,asc',
  })
  return `/api/admin/artists?${params}`
}

/** The EXACT artists whose name contains [term], ignoring case: where a wrong match is found (#5381). */
export async function searchMatchedArtists(
  term: string,
  fetchFn: typeof fetch = fetch,
): Promise<ReviewArtist[]> {
  const response = await fetchFn(matchedSearchUrl(term), {
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, 'GET /api/admin/artists')
  return ((await response.json()) as ReviewPage).content
}

/**
 * Rejects artist [id]'s EXACT match (#5378): the importer clears what the enrichment copied, sets AMBIGUOUS, and never
 * picks that MBID for the row again.
 */
export async function rejectMusicBrainzMatch(
  id: number,
  fetchFn: typeof fetch = fetch,
): Promise<ReviewArtist> {
  const path = `/api/admin/artists/${id}/musicbrainz-id`
  const response = await fetchFn(path, {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, `DELETE ${path}`)
  return (await response.json()) as ReviewArtist
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
