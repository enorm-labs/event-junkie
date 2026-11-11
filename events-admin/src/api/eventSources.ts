import { ensureOk, fetchAllPages } from '@/api/http'

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

export { PAGE_SIZE } from '@/api/http'
const SOURCES = '/api/admin/event-sources'

/** Every source, sorted by name. Refuses a partial listing (#2595). */
export function fetchAllSources(fetchFn: typeof fetch = fetch): Promise<EventSource[]> {
  return fetchAllPages<EventSource>(
    SOURCES,
    { sort: 'name,asc', noun: 'sources', key: (source) => source.slug },
    fetchFn,
  )
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
