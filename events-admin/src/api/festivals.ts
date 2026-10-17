import { ensureOk } from './eventSources'

/** `FestivalRequest.kt`: the body of a create and of the PUT, which replaces the whole festival. */
export interface FestivalRequest {
  name: string
  /** The festival's own programme page. */
  url: string
  /** ISO dates, Berlin. `lastDay` is `firstDay` for a one-day festival. */
  firstDay: string
  lastDay: string
  noticeDe: string
  noticeEn: string
}

/** `FestivalResponse.kt`: the request's fields and the ones the importer sets. */
export interface Festival extends FestivalRequest {
  id: number
  slug: string
  createdAt: string | null
  updatedAt: string | null
  /** How many events link to the festival, past ones included (#3273). */
  eventCount: number
}

interface Page<T> {
  content: T[]
  totalElements: number
}

const FESTIVALS = '/api/admin/festivals'
const JSON_HEADERS = { Accept: 'application/json', 'Content-Type': 'application/json' }
const PAGE_SIZE = 100

/**
 * Every festival, the latest first day first. One page holds a hundred, years of them, so a
 * listing past one page is refused rather than shown in part, as `fetchAllVenues` does.
 */
export async function fetchFestivals(fetchFn: typeof fetch = fetch): Promise<Festival[]> {
  const path = `${FESTIVALS}?page=0&size=${PAGE_SIZE}&sort=firstDay,desc`
  const response = await fetchFn(path, { headers: { Accept: 'application/json' } })
  await ensureOk(response, `GET ${FESTIVALS}`)
  const body = (await response.json()) as Page<Festival>
  if (body.content.length !== body.totalElements) {
    throw new Error(
      `Read ${body.content.length} of ${body.totalElements} festivals. A partial listing is not shown.`,
    )
  }
  return body.content
}

/** The festivals whose days hold [eventDate] (`yyyy-MM-dd`), the ones an event of that date can belong to. */
export function festivalsOn(festivals: Festival[], eventDate: string): Festival[] {
  return festivals.filter(
    (festival) => festival.firstDay <= eventDate && eventDate <= festival.lastDay,
  )
}

/** Creates a festival when [id] is null, else replaces festival [id]. */
export async function saveFestival(
  id: number | null,
  request: FestivalRequest,
  fetchFn: typeof fetch = fetch,
): Promise<Festival> {
  const path = id === null ? FESTIVALS : `${FESTIVALS}/${id}`
  const method = id === null ? 'POST' : 'PUT'
  const response = await fetchFn(path, {
    method,
    headers: JSON_HEADERS,
    body: JSON.stringify(request),
  })
  await ensureOk(response, `${method} ${path}`)
  return (await response.json()) as Festival
}

/** Deletes festival [id]. The importer answers `204`. */
export async function deleteFestival(id: number, fetchFn: typeof fetch = fetch): Promise<void> {
  const path = `${FESTIVALS}/${id}`
  const response = await fetchFn(path, {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
  })
  await ensureOk(response, `DELETE ${path}`)
}
