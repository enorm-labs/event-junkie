/**
 * Problem reports from the public event page (#4399), and their resolution. Hand-written like
 * `dataQuality.ts`: `ReportAdminController.kt` in the importer is the source of truth.
 */
import { failOnError } from '@/api/http'

/** The boxes of "Report a problem", labelled as the operator reads them. `ReportProblem` in the BFF. */
export const PROBLEM_LABELS: Record<string, string> = {
  ARTISTS: 'Artists',
  TITLE: 'Title',
  DATE_TIME: 'Date or time',
  STATUS: 'Cancelled/postponed',
  PRICE_TICKETS: 'Price or tickets',
  TYPE_GENRE: 'Type or genre',
  NOT_AN_EVENT: 'Not an event',
  DUPLICATE: 'Duplicate',
  OTHER: 'Other',
}

export interface ReportEntry {
  problems: string[]
  note: string | null
  updatedAt: string
}

export interface ReportedEvent {
  eventId: number
  slug: string
  title: string
  eventDate: string
  venue: string | null
  /** `null` for a hand-entered event. */
  sourceSlug: string | null
  reports: ReportEntry[]
}

const REPORTS = '/api/admin/reports'

/** Every open report, one entry per event, by event date. */
export async function fetchReports(fetchFn: typeof fetch = fetch): Promise<ReportedEvent[]> {
  const response = await fetchFn(REPORTS, { headers: { Accept: 'application/json' } })
  await failOnError(response, `GET ${REPORTS}`)
  return (await response.json()) as ReportedEvent[]
}

/** Resolves (deletes) every report on the event. Answers how many went. */
export async function resolveReports(
  eventId: number,
  fetchFn: typeof fetch = fetch,
): Promise<number> {
  const path = `${REPORTS}?${new URLSearchParams({ eventId: String(eventId) })}`
  const response = await fetchFn(path, {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, `DELETE ${path}`)
  return ((await response.json()) as { resolved: number }).resolved
}
