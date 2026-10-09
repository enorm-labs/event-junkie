/**
 * The importer's data-quality worklist. Hand-written like `eventSources.ts`: `QualityIssue.kt` and
 * `DataQualityResponses.kt` are the source of truth.
 */

/** Each `QualityIssue` by its `?issue=` key, in the enum's order. */
export const QUALITY_ISSUES = [
  { key: 'concertsWithoutArtist', label: 'Concerts without an artist' },
  { key: 'eventsTypedOther', label: 'Events typed OTHER' },
  { key: 'missingGenre', label: 'Missing genre' },
  { key: 'missingPromoter', label: 'Missing promoter' },
  { key: 'missingPrice', label: 'Missing price' },
  { key: 'missingStartTime', label: 'Missing start time' },
  { key: 'unreviewedLicence', label: 'Unreviewed licence' },
  { key: 'titleDerivedSingletons', label: 'Title-derived singletons' },
  { key: 'titleDerivedUnmatched', label: 'Title-derived, no MusicBrainz match' },
  { key: 'flaggedAtImport', label: 'Flagged at import' },
] as const

export type QualityIssueKey = (typeof QUALITY_ISSUES)[number]['key']

export interface WorklistFlag {
  kind: string
  value: string
}

export interface WorklistEntry {
  id: number
  slug: string
  title: string
  eventDate: string
  startTime: string | null
  venueId: number
  sourceSlug: string
  flags: WorklistFlag[]
}

export interface Worklist {
  issue: string
  source: string | null
  count: number
  entries: WorklistEntry[]
}

/** The endpoint's default page. It accepts up to 500. */
export const WORKLIST_LIMIT = 50

export interface WorklistQuery {
  issue: QualityIssueKey
  /** A source slug, `manual` for hand-created events, or empty for every source. */
  source?: string
  offset?: number
  limit?: number
}

export function worklistUrl({ issue, source, offset = 0, limit = WORKLIST_LIMIT }: WorklistQuery) {
  const params = new URLSearchParams({ issue, limit: String(limit), offset: String(offset) })
  if (source) params.set('source', source)
  return `/api/admin/data-quality/worklist?${params}`
}

/** One page of the events failing [query.issue], newest event first. */
export async function fetchWorklist(
  query: WorklistQuery,
  fetchFn: typeof fetch = fetch,
): Promise<Worklist> {
  const response = await fetchFn(worklistUrl(query), { headers: { Accept: 'application/json' } })
  if (!response.ok) {
    throw new Error(`GET /api/admin/data-quality/worklist: HTTP ${response.status}`)
  }
  return (await response.json()) as Worklist
}
