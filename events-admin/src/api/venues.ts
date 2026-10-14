import { ensureOk } from './eventSources'

/**
 * The fields of the importer's `VenueResponse` this app reads. Hand-written: `VenueResponse.kt`
 * is the source of truth.
 */
export interface AdminVenue {
  id: number
  name: string
  slug: string
  /** The last day the venue was open; null while it is open. */
  closedOn: string | null
}

interface Page<T> {
  content: T[]
  totalElements: number
}

/** The importer caps a page at 100, whatever `size` asks for. */
const PAGE_SIZE = 100
const MAX_PAGES = 50

/** Every venue, sorted by name. Refuses a partial listing, as `fetchAllSources` does (#2595). */
export async function fetchAllVenues(fetchFn: typeof fetch = fetch): Promise<AdminVenue[]> {
  const byId = new Map<number, AdminVenue>()
  let total = 0
  for (let page = 0; page < MAX_PAGES; page++) {
    const response = await fetchFn(
      `/api/admin/venues?page=${page}&size=${PAGE_SIZE}&sort=name,asc`,
      { headers: { Accept: 'application/json' } },
    )
    if (!response.ok) {
      throw new Error(`GET /api/admin/venues page ${page}: HTTP ${response.status}`)
    }
    const body = (await response.json()) as Page<AdminVenue>
    total = body.totalElements
    for (const { id, name, slug, closedOn } of body.content) {
      byId.set(id, { id, name, slug, closedOn })
    }
    if (body.content.length === 0 || byId.size >= total) break
  }
  if (byId.size !== total) {
    throw new Error(`Read ${byId.size} of ${total} venues. A partial listing is not shown.`)
  }
  return [...byId.values()]
}

const VENUES = '/api/admin/venues'

/** What one site check found. `SiteOutcome.kt`; `needs-review` never lists `OK`. */
export type SiteOutcome =
  | 'OK'
  | 'HTTP'
  | 'DNS'
  | 'TLS'
  | 'TIMEOUT'
  | 'CONNECTION'
  | 'OTHER'
  | 'SKIPPED'

/** One row of `GET /api/admin/venues/needs-review`. `NeedsReviewResponse` in `VenueSiteCheckController.kt`. */
export interface NeedsReviewVenue {
  venueId: number
  slug: string
  name: string
  websiteUrl: string | null
  programmeUrl: string | null
  reviewedAt: string | null
  checkedAt: string
  /** The URL whose failure the last check reports. */
  url: string | null
  outcome: SiteOutcome
  /** Set for an `HTTP` outcome only. */
  httpStatus: number | null
  consecutiveFailures: number
  failingSince: string | null
}

/**
 * Venues without an importer whose site failed three monthly checks in a row (#2812). The importer
 * sorts them by `failingSince`, so the longest-failing venue comes first.
 */
export async function fetchNeedsReview(fetchFn: typeof fetch = fetch): Promise<NeedsReviewVenue[]> {
  const path = `${VENUES}/needs-review`
  const response = await fetchFn(path, { headers: { Accept: 'application/json' } })
  await ensureOk(response, `GET ${path}`)
  return (await response.json()) as NeedsReviewVenue[]
}

/** Starts one site-check pass. The importer answers `202` and probes in the background. */
export async function runSiteCheck(fetchFn: typeof fetch = fetch): Promise<void> {
  const path = `${VENUES}/site-check`
  const response = await fetchFn(path, { method: 'POST', headers: { Accept: 'application/json' } })
  await ensureOk(response, `POST ${path}`)
}

/**
 * `VenueRequest.kt`: the body of `PUT /api/admin/venues/{id}`. The PUT replaces the whole venue,
 * so a field left out resets to its default.
 */
export interface VenueRequest {
  name: string
  address: string | null
  city: string
  postalCode: string | null
  district: string | null
  latitude: number | null
  longitude: number | null
  websiteUrl: string | null
  instagramUrl: string | null
  facebookUrl: string | null
  imageUrl: string | null
  imageAttribution: string | null
  imageLicenceId: string | null
  imageSourceUrl: string | null
  description: string | null
  descriptionLanguage: string | null
  descriptionAlt: string | null
  descriptionAltLanguage: string | null
  venueTypes: string[]
  capacity: number | null
  programmeUrl: string | null
  /** An ISO instant. */
  reviewedAt: string | null
  /** An ISO date: the last day the venue was open. */
  closedOn: string | null
}

/**
 * `VenueResponse.kt`: every field of `VenueRequest`, and the ones the database or the events
 * set. `programmeFamilies` and `programmeEventTypes` come from the venue's events and are not
 * part of a PUT.
 */
export interface VenueDetail extends VenueRequest {
  id: number
  slug: string
  programmeFamilies: string[]
  programmeEventTypes: string[]
  createdAt: string | null
  updatedAt: string | null
}

/** The one field a review changes. */
export type VenueReview = Pick<VenueRequest, 'reviewedAt'> | Pick<VenueRequest, 'closedOn'>

/** The PUT body that sends [venue] back as read, with only [review]'s field changed. */
export function toVenueRequest(venue: VenueDetail, review: VenueReview): VenueRequest {
  return {
    name: venue.name,
    address: venue.address,
    city: venue.city,
    postalCode: venue.postalCode,
    district: venue.district,
    latitude: venue.latitude,
    longitude: venue.longitude,
    websiteUrl: venue.websiteUrl,
    instagramUrl: venue.instagramUrl,
    facebookUrl: venue.facebookUrl,
    imageUrl: venue.imageUrl,
    imageAttribution: venue.imageAttribution,
    imageLicenceId: venue.imageLicenceId,
    imageSourceUrl: venue.imageSourceUrl,
    description: venue.description,
    descriptionLanguage: venue.descriptionLanguage,
    descriptionAlt: venue.descriptionAlt,
    descriptionAltLanguage: venue.descriptionAltLanguage,
    venueTypes: venue.venueTypes,
    capacity: venue.capacity,
    programmeUrl: venue.programmeUrl,
    reviewedAt: venue.reviewedAt,
    closedOn: venue.closedOn,
    ...review,
  }
}

/**
 * Reads venue [id] and writes it back with only [review]'s field changed. The read comes right
 * before the write, so the PUT does not undo an edit made since the page loaded.
 */
export async function reviewVenue(
  id: number,
  review: VenueReview,
  fetchFn: typeof fetch = fetch,
): Promise<VenueDetail> {
  const path = `${VENUES}/${id}`
  const read = await fetchFn(path, { headers: { Accept: 'application/json' } })
  await ensureOk(read, `GET ${path}`)
  const venue = (await read.json()) as VenueDetail
  const response = await fetchFn(path, {
    method: 'PUT',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(toVenueRequest(venue, review)),
  })
  await ensureOk(response, `PUT ${path}`)
  return (await response.json()) as VenueDetail
}

/**
 * `VenueCharacterTag.kt` in the importer, in the enum's order, which is the display order (#2379).
 * The labels are the public site's English ones (`venueCharacter.json`).
 */
export const VENUE_CHARACTER_TAGS = [
  { slug: 'queer', label: 'Queer' },
  { slug: 'sex-positive', label: 'Sex-positive' },
  { slug: 'diy-collective', label: 'DIY / collective' },
  { slug: 'awareness-team', label: 'Awareness team' },
  { slug: 'safer-space-policy', label: 'Safer-space policy' },
  { slug: 'quiet-room', label: 'Quiet room' },
  { slug: 'all-gender-toilets', label: 'All-gender toilets' },
  { slug: 'free-water', label: 'Free water at the bar' },
  { slug: 'smoke-free', label: 'Smoke-free' },
  { slug: 'dress-code', label: 'Dress code' },
  { slug: 'fetish-dress-code', label: 'Fetish dress code' },
  { slug: 'no-photo-policy', label: 'No-photo policy' },
  { slug: 'cash-only', label: 'Cash only' },
  { slug: 'wheelchair-accessible', label: 'Wheelchair accessible' },
] as const

/** `VenueCharacterTagResponse`: a tag and the venue's own page that states it. */
export interface CharacterTag {
  tag: string
  sourceUrl: string
}

/**
 * Whether [value] can be a tag's source URL here: `https://` and no whitespace. The importer also
 * takes `http://`; this app asks for the stricter form.
 */
export function isHttpsUrl(value: string): boolean {
  if (!/^https:\/\/\S+$/.test(value) || value.length > 2048) return false
  try {
    return new URL(value).hostname !== ''
  } catch {
    return false
  }
}

const tagsPath = (venueId: number) => `${VENUES}/${venueId}/character-tags`

export async function fetchCharacterTags(
  venueId: number,
  fetchFn: typeof fetch = fetch,
): Promise<CharacterTag[]> {
  const path = tagsPath(venueId)
  const response = await fetchFn(path, { headers: { Accept: 'application/json' } })
  await ensureOk(response, `GET ${path}`)
  return (await response.json()) as CharacterTag[]
}

/** Sets [tag] on the venue, or replaces its source URL. Refuses a URL `isHttpsUrl` refuses. */
export async function setCharacterTag(
  venueId: number,
  tag: string,
  sourceUrl: string,
  fetchFn: typeof fetch = fetch,
): Promise<CharacterTag> {
  if (!isHttpsUrl(sourceUrl)) throw new Error(`Not an https:// URL: ${sourceUrl}`)
  const path = `${tagsPath(venueId)}/${encodeURIComponent(tag)}`
  const response = await fetchFn(path, {
    method: 'PUT',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ sourceUrl }),
  })
  await ensureOk(response, `PUT ${path}`)
  return (await response.json()) as CharacterTag
}

/** Removes [tag] from the venue. The importer answers `204`. */
export async function clearCharacterTag(
  venueId: number,
  tag: string,
  fetchFn: typeof fetch = fetch,
): Promise<void> {
  const path = `${tagsPath(venueId)}/${encodeURIComponent(tag)}`
  const response = await fetchFn(path, {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, `DELETE ${path}`)
}
