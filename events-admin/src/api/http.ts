/**
 * What every admin API module shares: the page envelope, the error check and the GET helpers.
 * The importer answers a refused request with an RFC 9457 Problem Detail (`GlobalExceptionHandler.kt`).
 */

/** The fields of the importer's `PageResponse` this app reads. */
export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
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

/** [response] when it is a success. Else an error naming [what], the status and the Problem Detail. */
export async function failOnError(response: Response, what: string): Promise<Response> {
  if (response.ok) return response
  const detail = await response
    .json()
    .then((body: ProblemDetail) => problemText(body))
    .catch(() => null)
  throw new Error(`${what}: HTTP ${response.status}${detail ? ` — ${detail}` : ''}`)
}

export async function getJson<T>(url: string, fetchFn: typeof fetch = fetch): Promise<T> {
  const response = await fetchFn(url, { headers: { Accept: 'application/json' } })
  await failOnError(response, `GET ${url}`)
  return (await response.json()) as T
}

/** The importer caps a page at 100, whatever `size` asks for. */
export const PAGE_SIZE = 100
const MAX_PAGES = 50

export interface AllPagesQuery<T> {
  /** The `sort` parameter, as `name,asc`. */
  sort: string
  /** What the rows are, for the error of a partial listing. */
  noun: string
  /** A row that moves between pages while they are read is kept once. */
  key: (row: T) => unknown
}

/**
 * Every row of [path]. Pages until `totalElements` is reached and refuses a partial listing, as
 * `scripts/force-import.py` does: one page reads as a complete list (#2595).
 */
export async function fetchAllPages<T>(
  path: string,
  { sort, noun, key }: AllPagesQuery<T>,
  fetchFn: typeof fetch = fetch,
): Promise<T[]> {
  const byKey = new Map<unknown, T>()
  let total = 0
  for (let page = 0; page < MAX_PAGES; page++) {
    const response = await fetchFn(`${path}?page=${page}&size=${PAGE_SIZE}&sort=${sort}`, {
      headers: { Accept: 'application/json' },
    })
    await failOnError(response, `GET ${path} page ${page}`)
    const body = (await response.json()) as Page<T>
    total = body.totalElements
    for (const row of body.content) byKey.set(key(row), row)
    if (body.content.length === 0 || byKey.size >= total) break
  }
  if (byKey.size !== total) {
    throw new Error(`Read ${byKey.size} of ${total} ${noun}. A partial listing is not shown.`)
  }
  return [...byKey.values()]
}
