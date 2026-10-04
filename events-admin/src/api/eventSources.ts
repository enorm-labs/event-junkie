/**
 * The fields of the importer's `EventSourceResponse` this app reads. Hand-written: no generated
 * schema covers the admin API yet, and `EventSourceResponses.kt` is the source of truth.
 */
export interface EventSource {
  id: number
  slug: string
  name: string
  url: string
  sourceType: string
  enabled: boolean
  status: string
  lastImportAt: string | null
  lastSuccessAt: string | null
  lastEventCount: number | null
  lastFailureReason: string | null
  flaggedAt: string | null
}

interface Page<T> {
  content: T[]
  totalElements: number
}

/** The importer caps a page at 100, whatever `size` asks for. */
export const PAGE_SIZE = 100
const MAX_PAGES = 50

/**
 * Every source, sorted by name. Pages until `totalElements` is reached and refuses a partial
 * listing, as `scripts/force-import.py` does: one page reads as a complete list (#2595).
 */
export async function fetchAllSources(fetchFn: typeof fetch = fetch): Promise<EventSource[]> {
  const bySlug = new Map<string, EventSource>()
  let total = 0
  for (let page = 0; page < MAX_PAGES; page++) {
    const response = await fetchFn(
      `/api/admin/event-sources?page=${page}&size=${PAGE_SIZE}&sort=name,asc`,
      { headers: { Accept: 'application/json' } },
    )
    if (!response.ok) {
      throw new Error(`GET /api/admin/event-sources page ${page}: HTTP ${response.status}`)
    }
    const body = (await response.json()) as Page<EventSource>
    total = body.totalElements
    for (const source of body.content) bySlug.set(source.slug, source)
    if (body.content.length === 0 || bySlug.size >= total) break
  }
  if (bySlug.size !== total) {
    throw new Error(`Read ${bySlug.size} of ${total} sources. A partial listing is not shown.`)
  }
  return [...bySlug.values()]
}
