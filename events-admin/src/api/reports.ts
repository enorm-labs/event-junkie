/**
 * Problem reports from the public event, act, promoter and venue pages (#4399, #5603), and their
 * resolution. Hand-written like
 * `dataQuality.ts`: `ReportAdminController.kt` in the importer is the source of truth.
 */
import { failOnError } from '@/api/http'

/**
 * The boxes of "Report a problem", labelled as the operator reads them. `ReportProblem` in the BFF:
 * the event form's boxes and hint, the names of earlier forms that stored reports can still hold,
 * then the boxes of acts, promoters and venues. `OTHER` is a report with a note and no box.
 */
export const PROBLEM_LABELS: Record<string, string> = {
  ARTISTS: 'Artists',
  PROMOTER: 'Promoter',
  GENRE: 'Genre',
  EVENT_TYPE: 'Event type',
  FEATURES: 'Features',
  DATE_TIME: 'Date or time',
  TEXT: 'Text',
  STATUS: 'Cancelled/sold out/moved',
  PRICE_TICKETS: 'Price or tickets',
  NOT_AN_EVENT: 'Not an event or duplicate',
  ON_IMAGE: 'On the image',
  TITLE: 'Title',
  DESCRIPTION: 'Description',
  TYPE_GENRE: 'Type or genre',
  DUPLICATE: 'Duplicate',
  OTHER: 'Other',
  QUESTION: 'Question',
  NAME: 'Name',
  ADDRESS: 'Address or pin',
  LINKS: 'Links',
  CLOSED: 'Closed',
  WRONG_ACT: 'Another act',
  IMAGE: 'Picture',
  NOT_A_PROMOTER: 'Not a promoter',
}

/** What a report is about, as its page's path names it. */
export type ReportKind = 'events' | 'artists' | 'promoters' | 'venues'

export const KIND_LABELS: Record<ReportKind, string> = {
  events: 'Event',
  artists: 'Act',
  promoters: 'Promoter',
  venues: 'Venue',
}

export interface ReportEntry {
  problems: string[]
  note: string | null
  updatedAt: string
}

export interface ReportedSubject {
  kind: ReportKind
  id: number
  slug: string
  /** The event's title, or the act's, promoter's or venue's name. */
  name: string
  /** The event's date, venue and source; `null` on the other kinds. */
  eventDate: string | null
  venue: string | null
  /** `null` for a hand-entered event and on the other kinds. */
  sourceSlug: string | null
  reports: ReportEntry[]
}

const REPORTS = '/api/admin/reports'

/** Every open report, one entry per subject: events by date, then acts, promoters and venues. */
export async function fetchReports(fetchFn: typeof fetch = fetch): Promise<ReportedSubject[]> {
  const response = await fetchFn(REPORTS, { headers: { Accept: 'application/json' } })
  await failOnError(response, `GET ${REPORTS}`)
  return (await response.json()) as ReportedSubject[]
}

/** Resolves (deletes) every report on the subject. Answers how many went. */
export async function resolveReports(
  subject: Pick<ReportedSubject, 'kind' | 'id'>,
  fetchFn: typeof fetch = fetch,
): Promise<number> {
  const path = `${REPORTS}?${new URLSearchParams({ kind: subject.kind, id: String(subject.id) })}`
  const response = await fetchFn(path, {
    method: 'DELETE',
    headers: { Accept: 'application/json' },
  })
  await failOnError(response, `DELETE ${path}`)
  return ((await response.json()) as { resolved: number }).resolved
}
