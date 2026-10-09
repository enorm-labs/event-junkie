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
