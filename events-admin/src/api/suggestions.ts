/**
 * The importer's suggestion store: the fixes a data-quality check proposes. Hand-written:
 * `EventSuggestionController.kt` and `EventClassificationCheck.kt` are the source of truth.
 *
 * A check only proposes (ADR-041). Accept applies the value with `PUT /api/admin/events/{id}`, or
 * `PUT /api/admin/events/{id}/features` for features. Both pin the field (ADR-042). Then the suggestion
 * is marked accepted.
 */
import {
  type AdminEvent,
  type EventArtist,
  type EventRequest,
  EVENT_TYPES,
  type EventFeatures,
  type EventType,
  failOnError,
  fetchEvent,
  fetchEventFeatures,
  PARTY_FEATURES,
  parsePrice,
  setEventFeatures,
  toEventRequest,
  updateEvent,
} from '@/api/events'
import { createArtist, fetchNames, findArtistByName } from '@/api/names'

export type SuggestionStatus = 'OPEN' | 'DISMISSED' | 'ACCEPTED'

export interface Suggestion {
  id: number
  eventId: number
  eventTitle: string
  eventSlug: string
  eventDate: string
  venueName: string
  field: string
  /** Null when the event holds no value for the field. */
  storedValue: string | null
  proposedValue: string
  evidence: string
  confidence: number
  checkName: string
  /** Null for a deterministic check. */
  model: string | null
  status: SuggestionStatus
  createdAt: string
}

export interface SuggestionPage {
  content: Suggestion[]
  totalElements: number
  totalPages: number
}

/** The checks that write suggestions, for the filter. */
export const SUGGESTION_CHECKS = ['event-classification'] as const

export const SUGGESTION_PAGE_SIZE = 20

/** The pin each field this app applies sets. The lineup is a join table, so `artists` pins `lineup`. */
const PINS: Record<string, string> = {
  eventType: 'eventType',
  artists: 'lineup',
  startTime: 'startTime',
  pricePresale: 'pricePresale',
  priceBoxOffice: 'priceBoxOffice',
}

/** Why Accept is off for [field], or null when this app can apply it. */
export function cannotApply(field: string): string | null {
  if (field === 'features') return null
  if (!(field in PINS)) return `This app cannot apply a "${field}" suggestion.`
  return null
}

/** The `artists` and `features` values are lists joined by "; ". An empty value means none. */
export function listItems(value: string): string[] {
  return value
    .split(';')
    .map((name) => name.trim())
    .filter((name) => name !== '')
}

export interface SuggestionQuery {
  page?: number
  /** A check name, or empty for every check. */
  checkName?: string
}

export function suggestionsUrl({ page = 0, checkName }: SuggestionQuery) {
  const params = new URLSearchParams({
    status: 'OPEN',
    page: String(page),
    size: String(SUGGESTION_PAGE_SIZE),
  })
  if (checkName) params.set('checkName', checkName)
  return `/api/admin/data-quality/suggestions?${params}`
}

/** One page of the open suggestions, newest first. */
export async function fetchSuggestions(
  query: SuggestionQuery,
  fetchFn: typeof fetch = fetch,
): Promise<SuggestionPage> {
  const response = await fetchFn(suggestionsUrl(query), {
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, 'GET /api/admin/data-quality/suggestions')
  return (await response.json()) as SuggestionPage
}

async function setStatus(
  id: number,
  action: 'accept' | 'dismiss',
  fetchFn: typeof fetch,
): Promise<Suggestion> {
  const path = `/api/admin/data-quality/suggestions/${id}/${action}`
  const response = await fetchFn(path, { method: 'POST', headers: { Accept: 'application/json' } })
  await failOnError(response, `POST ${path}`)
  return (await response.json()) as Suggestion
}

/** Marks [id] accepted. It writes no event field: [applySuggestion] does that first. */
export function acceptSuggestion(id: number, fetchFn: typeof fetch = fetch) {
  return setStatus(id, 'accept', fetchFn)
}

/** Marks [id] dismissed. The event stays as it is. */
export function dismissSuggestion(id: number, fetchFn: typeof fetch = fetch) {
  return setStatus(id, 'dismiss', fetchFn)
}

/**
 * The lineup [names] describe, in their order. An act the event bills keeps its role and stage. Any
 * other act is the artist of that name, created when none exists, as support, or headliner when
 * it comes first.
 */
async function proposedLineup(
  event: AdminEvent,
  names: string[],
  fetchFn: typeof fetch,
): Promise<EventArtist[]> {
  const stored = await fetchNames(
    'artists',
    event.artists.map((artist) => artist.artistId),
    fetchFn,
  )
  const lineup: EventArtist[] = []
  for (const [index, name] of names.entries()) {
    const billed = event.artists.find(
      (artist) => stored.get(artist.artistId)?.name.toLowerCase() === name.toLowerCase(),
    )
    if (billed) {
      lineup.push({ ...billed, billingOrder: index })
      continue
    }
    const artist = (await findArtistByName(name, fetchFn)) ?? (await createArtist(name, fetchFn))
    if (lineup.some((entry) => entry.artistId === artist.id)) continue
    lineup.push({
      artistId: artist.id,
      role: index === 0 ? 'HEADLINER' : 'SUPPORT',
      billingOrder: index,
      stage: null,
    })
  }
  return lineup
}

/** [request] with the suggested value of [suggestion] in its field. */
async function withProposal(
  request: EventRequest,
  event: AdminEvent,
  suggestion: Suggestion,
  fetchFn: typeof fetch,
): Promise<EventRequest> {
  const value = suggestion.proposedValue
  switch (suggestion.field) {
    case 'eventType':
      if (!EVENT_TYPES.includes(value as EventType)) throw new Error(`"${value}" is no event type.`)
      return { ...request, eventType: value as EventType }
    case 'startTime':
      if (!/^\d{2}:\d{2}$/.test(value)) throw new Error(`"${value}" is no time.`)
      return { ...request, startTime: value }
    case 'pricePresale':
      return { ...request, pricePresale: parsePrice(value) }
    case 'priceBoxOffice':
      return { ...request, priceBoxOffice: parsePrice(value) }
    case 'artists':
      return { ...request, artists: await proposedLineup(event, listItems(value), fetchFn) }
    default:
      throw new Error(cannotApply(suggestion.field) ?? `Cannot apply "${suggestion.field}".`)
  }
}

const FEATURE_SLUGS: readonly string[] = PARTY_FEATURES.map((feature) => feature.slug)

function sameSet(a: string[], b: string[]) {
  const left = new Set(a)
  const right = new Set(b)
  return left.size === right.size && [...left].every((item) => right.has(item))
}

/**
 * Replaces the features of the event with the proposed set, which already holds the stored ones.
 * A blind PUT would drop a feature added since the check ran, so a changed event is refused.
 */
async function applyFeatures(
  suggestion: Suggestion,
  fetchFn: typeof fetch,
): Promise<EventFeatures> {
  const proposed = listItems(suggestion.proposedValue)
  const unknown = proposed.filter((slug) => !FEATURE_SLUGS.includes(slug))
  if (unknown.length > 0) throw new Error(`"${unknown.join('", "')}" is no party feature.`)
  const current = await fetchEventFeatures(suggestion.eventId, fetchFn)
  const stored = current.features.map((entry) => entry.feature)
  if (!sameSet(stored, listItems(suggestion.storedValue ?? ''))) {
    throw new Error(
      'The features of the event changed since the check ran. ' +
        'The suggestion stays open: reject it, or edit the event.',
    )
  }
  const saved = await setEventFeatures(suggestion.eventId, proposed, fetchFn)
  await acceptSuggestion(suggestion.id, fetchFn)
  return saved
}

/**
 * Applies [suggestion] to its event and marks it accepted. The PUT sends every other field as it
 * was read, so only the suggested field changes. When the answer does not pin that field, the
 * suggestion stays open and this throws: nothing changed, and accepting would claim a fix.
 */
export async function applySuggestion(
  suggestion: Suggestion,
  fetchFn: typeof fetch = fetch,
): Promise<AdminEvent | EventFeatures> {
  if (suggestion.field === 'features') return applyFeatures(suggestion, fetchFn)
  const pin = PINS[suggestion.field]
  if (!pin) throw new Error(cannotApply(suggestion.field) ?? `Cannot apply "${suggestion.field}".`)
  const event = await fetchEvent(suggestion.eventId, fetchFn)
  // The genre goes back untrimmed, as read, so the PUT does not pin it.
  const unchanged = {
    ...toEventRequest(event, { eventType: event.eventType, genre: event.genre ?? '' }),
    genre: event.genre,
  }
  const request = await withProposal(unchanged, event, suggestion, fetchFn)
  const saved = await updateEvent(event.id, request, fetchFn)
  if (!saved.pinnedFields.includes(pin)) {
    throw new Error(
      `The event already holds this value, so the PUT pinned no "${pin}". ` +
        'The suggestion stays open: reject it, or edit the event.',
    )
  }
  await acceptSuggestion(suggestion.id, fetchFn)
  return saved
}
