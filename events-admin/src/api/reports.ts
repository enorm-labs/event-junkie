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

/** What the operator did about a page's reports (#5308): the bell's note says it to each reporter. */
export type ReportOutcome = 'fixed' | 'unchanged'

/** The longest reply, as the importer and V290 hold it. */
export const MAX_REPLY = 500

/**
 * Resolves every report on the subject: deletes them, and leaves each reporter a note under the
 * bell with the outcome and the reply (#5308). The reply goes in the body, never the URL, because
 * the ingress logs URLs. Answers how many reports went.
 */
export async function resolveReports(
  subject: Pick<ReportedSubject, 'kind' | 'id'>,
  outcome: ReportOutcome = 'fixed',
  reply = '',
  fetchFn: typeof fetch = fetch,
): Promise<number> {
  const path = `${REPORTS}/resolve`
  const response = await fetchFn(path, {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({
      kind: subject.kind,
      id: subject.id,
      outcome,
      reply: reply.trim() || undefined,
    }),
  })
  await failOnError(response, `POST ${path}`)
  return ((await response.json()) as { resolved: number }).resolved
}

/** An account as `GET /api/admin/accounts` answers it, the fields the reports page reads. */
interface AccountRow {
  email: string
  canReport: boolean
  openReports: number
}

export interface Reporter {
  email: string
  openReports: number
}

const ACCOUNTS = '/api/admin/accounts'

/**
 * The accounts that hold open reports, the most first (#5604). Every account may report, so one
 * account filling the queue shows here before it shows in the table.
 */
export async function fetchReporters(fetchFn: typeof fetch = fetch): Promise<Reporter[]> {
  const response = await fetchFn(ACCOUNTS, { headers: { Accept: 'application/json' } })
  await failOnError(response, `GET ${ACCOUNTS}`)
  return ((await response.json()) as AccountRow[])
    .filter((account) => account.openReports > 0)
    .map(({ email, openReports }) => ({ email, openReports }))
    .sort((a, b) => b.openReports - a.openReports)
}
