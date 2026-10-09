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
  importIntervalMinutes: number
  maxRetries: number
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
const SOURCES = '/api/admin/event-sources'

/**
 * Every source, sorted by name. Pages until `totalElements` is reached and refuses a partial
 * listing, as `scripts/force-import.py` does: one page reads as a complete list (#2595).
 */
export async function fetchAllSources(fetchFn: typeof fetch = fetch): Promise<EventSource[]> {
  const bySlug = new Map<string, EventSource>()
  let total = 0
  for (let page = 0; page < MAX_PAGES; page++) {
    const response = await fetchFn(`${SOURCES}?page=${page}&size=${PAGE_SIZE}&sort=name,asc`, {
      headers: { Accept: 'application/json' },
    })
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

/**
 * Throws for a response that is not 2xx. The message names the call and the status, and adds the
 * importer's `ProblemDetail.detail` when the body carries one (`GlobalExceptionHandler.kt`).
 */
async function ensureOk(response: Response, call: string): Promise<void> {
  if (response.ok) return
  let detail = ''
  try {
    const body = (await response.json()) as { detail?: unknown; errors?: unknown }
    if (typeof body.detail === 'string' && body.detail) detail = `: ${body.detail}`
    // A validation 400 says only "Validation failed" in `detail`; the reasons are in `errors`.
    if (Array.isArray(body.errors)) {
      const reasons = body.errors
        .map((e: { message?: unknown }) => e?.message)
        .filter((m): m is string => typeof m === 'string' && m !== '')
      if (reasons.length) detail += ` (${reasons.join('; ')})`
    }
  } catch {
    // No JSON body: the status alone is the message.
  }
  throw new Error(`${call}: HTTP ${response.status}${detail}`)
}

/** One source, read again after an action changed it. */
export async function fetchSource(
  slug: string,
  fetchFn: typeof fetch = fetch,
): Promise<EventSource> {
  const path = `${SOURCES}/${encodeURIComponent(slug)}`
  const response = await fetchFn(path, { headers: { Accept: 'application/json' } })
  await ensureOk(response, `GET ${path}`)
  return (await response.json()) as EventSource
}

/**
 * Whether `current` shows a run that ended after the click that read `lastImportAtBefore`. The
 * importer claims the source in the background after its `202`, so a read straight after can still
 * show the previous run's `SUCCESS`: the status alone does not say the new run ended. Every run
 * writes `lastImportAt` when it claims the source and again when it ends (`EventImportService.kt`),
 * so a later `lastImportAt` and a status other than `RUNNING` mark the end of a newer run.
 */
export function importEnded(lastImportAtBefore: string | null, current: EventSource): boolean {
  if (current.status === 'RUNNING' || current.lastImportAt === null) return false
  return (
    lastImportAtBefore === null || Date.parse(current.lastImportAt) > Date.parse(lastImportAtBefore)
  )
}

/**
 * Starts an import of one source. The importer answers `202` and imports in the background, so
 * the source read straight after can still show its old status (see `importEnded`). `force`
 * fetches the page without the cached `ETag` and `Last-Modified`, for a parser fix at a page that
 * has not changed (#1159).
 */
export async function triggerImport(
  slug: string,
  force: boolean,
  fetchFn: typeof fetch = fetch,
): Promise<void> {
  const path = `${SOURCES}/${encodeURIComponent(slug)}/import${force ? '?force=true' : ''}`
  const response = await fetchFn(path, { method: 'POST', headers: { Accept: 'application/json' } })
  await ensureOk(response, `POST ${path}`)
}

/** The fields of `EventSourceUpdateRequest.kt` this app edits. A missing field stays unchanged. */
export type SourceUpdate = Partial<
  Pick<EventSource, 'enabled' | 'importIntervalMinutes' | 'maxRetries'>
>

/**
 * Changes a source's configuration and answers the source as stored. The importer validates
 * `importIntervalMinutes >= 1` and `maxRetries >= 0` and answers `400` otherwise.
 */
export async function updateSource(
  slug: string,
  update: SourceUpdate,
  fetchFn: typeof fetch = fetch,
): Promise<EventSource> {
  const path = `${SOURCES}/${encodeURIComponent(slug)}`
  const response = await fetchFn(path, {
    method: 'PATCH',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify(update),
  })
  await ensureOk(response, `PATCH ${path}`)
  return (await response.json()) as EventSource
}

/**
 * Resets a `FAILED` or stuck `RUNNING` source to `IDLE`, clears its error and its retry count.
 * The scheduler imports it on its next tick.
 */
export async function retrySource(
  slug: string,
  fetchFn: typeof fetch = fetch,
): Promise<EventSource> {
  const path = `${SOURCES}/${encodeURIComponent(slug)}/retry`
  const response = await fetchFn(path, { method: 'POST', headers: { Accept: 'application/json' } })
  await ensureOk(response, `POST ${path}`)
  return (await response.json()) as EventSource
}
