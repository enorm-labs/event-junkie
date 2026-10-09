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

interface EventArtist {
  artistId: number
  role: string
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

/** `EventRequest`: the body of a POST or a PUT. */
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
}

/**
 * The full PUT body for [event] with [edit] applied. The lineup goes back with the four values
 * the importer compares, so an unchanged lineup pins nothing.
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
    artists: event.artists.map(({ artistId, role, billingOrder, stage }) => ({
      artistId,
      role,
      billingOrder,
      stage,
    })),
    promoterIds: event.promoterIds,
  }
}

interface ProblemDetail {
  detail?: unknown
  errors?: { field?: unknown; message?: unknown }[]
}

/** The `detail` of a Problem Detail, with the field errors a failed validation lists. */
function problemText(body: ProblemDetail): string | null {
  const detail = typeof body.detail === 'string' ? body.detail : null
  const fields = Array.isArray(body.errors)
    ? body.errors.map((e) => `${String(e.field)}: ${String(e.message)}`)
    : []
  if (fields.length === 0) return detail
  return `${detail ?? 'Validation failed'} (${fields.join('; ')})`
}

async function failOnError(response: Response, what: string): Promise<Response> {
  if (response.ok) return response
  // The importer answers a refused request with an RFC 9457 Problem Detail.
  const detail = await response
    .json()
    .then((body: ProblemDetail) => problemText(body))
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

/**
 * Latin letters that NFD cannot split into a base letter and a mark. The same table as
 * `SlugGenerator.kt`, so a title slugs here as it does in the importer.
 */
const NON_DECOMPOSING_LATIN: Record<string, string> = {
  ø: 'o',
  æ: 'ae',
  ð: 'd',
  þ: 'th',
  ł: 'l',
  đ: 'd',
  ı: 'i',
  ß: 'ss',
  œ: 'oe',
}

/** A lower-case ASCII slug: "Die Ärzte & Co." → "die-arzte-co". */
export function slugify(input: string): string {
  return input
    .toLowerCase()
    .replace(/[øæðþłđıßœ]/g, (letter) => NON_DECOMPOSING_LATIN[letter] ?? '')
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .replace(/[^a-z0-9]+/g, '-')
    .replace(/^-+|-+$/g, '')
}

/** `EventRequest.sourceId` is at most 255 characters. */
const SOURCE_ID_MAX = 255

/**
 * The `sourceId` of a hand-entered event: `manual:<venueSlug>:<eventDate>-<title slug>`. Two
 * entries of one title at one venue on one date get the same id, and the second is refused
 * with a 409, which is the duplicate check.
 */
export function manualSourceId(venueSlug: string, eventDate: string, title: string): string {
  const id = `manual:${venueSlug}:${eventDate}-${slugify(title)}`
  return id.slice(0, SOURCE_ID_MAX).replace(/-+$/, '')
}

/** What the New event form holds. Every field is the input's text; blank means "none". */
export interface NewEventForm {
  venueId: number | null
  title: string
  eventDate: string
  doorsTime: string
  startTime: string
  eventType: EventType
  genre: string
  ticketUrl: string
  sourceUrl: string
  pricePresale: string
  priceBoxOffice: string
  free: boolean
}

export function emptyNewEventForm(): NewEventForm {
  return {
    venueId: null,
    title: '',
    eventDate: '',
    doorsTime: '',
    startTime: '',
    eventType: 'CONCERT',
    genre: '',
    ticketUrl: '',
    sourceUrl: '',
    pricePresale: '',
    priceBoxOffice: '',
    free: false,
  }
}

function blankToNull(value: string): string | null {
  const trimmed = value.trim()
  return trimmed === '' ? null : trimmed
}

/** A price as typed, with a decimal comma or point. Blank is null; anything else unparsable throws. */
export function parsePrice(value: string): number | null {
  const text = blankToNull(value)
  if (text === null) return null
  const price = Number(text.replace(',', '.'))
  if (!Number.isFinite(price) || price < 0) throw new Error(`"${text}" is not a price.`)
  return price
}

/**
 * The POST body for [form] at the venue with [venueSlug]. A hand-entered event has no
 * description, image, lineup or promoters: those wait for the name search of #345.
 */
export function toCreateRequest(form: NewEventForm, venueSlug: string): EventRequest {
  if (form.venueId === null) throw new Error('Pick a venue.')
  const title = form.title.trim()
  if (title === '') throw new Error('Enter a title.')
  if (form.eventDate === '') throw new Error('Enter a date.')
  return {
    venueId: form.venueId,
    title,
    subtitle: null,
    description: null,
    eventType: form.eventType,
    status: 'SCHEDULED',
    eventDate: form.eventDate,
    doorsTime: blankToNull(form.doorsTime),
    startTime: blankToNull(form.startTime),
    imageUrl: null,
    sourceUrl: blankToNull(form.sourceUrl),
    sourceId: manualSourceId(venueSlug, form.eventDate, title),
    ticketUrl: blankToNull(form.ticketUrl),
    facebookEventUrl: null,
    genre: blankToNull(form.genre),
    pricePresale: parsePrice(form.pricePresale),
    priceBoxOffice: parsePrice(form.priceBoxOffice),
    priceCurrency: 'EUR',
    priceNote: null,
    soldOut: false,
    free: form.free,
    artists: [],
    promoterIds: [],
  }
}

/** Creates an event. The importer answers 201 with the stored event. */
export async function createEvent(
  request: EventRequest,
  fetchFn: typeof fetch = fetch,
): Promise<AdminEvent> {
  const response = await fetchFn('/api/admin/events', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(request),
  })
  await failOnError(response, 'POST /api/admin/events')
  return (await response.json()) as AdminEvent
}

/**
 * The events [venueId] already holds on [eventDate] (`yyyy-MM-dd`), in start-time order. The New
 * event form reads them before a save, so a second entry of one night with another title is seen.
 * One page of 100 is enough: no venue has that many events on one date.
 */
export async function fetchEventsOn(
  venueId: number,
  eventDate: string,
  fetchFn: typeof fetch = fetch,
): Promise<AdminEvent[]> {
  const query = new URLSearchParams({
    venueId: String(venueId),
    date: eventDate,
    size: '100',
    sort: 'startTime,asc',
  })
  const response = await fetchFn(`/api/admin/events?${query}`, {
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, 'GET /api/admin/events')
  return ((await response.json()) as { content: AdminEvent[] }).content
}
