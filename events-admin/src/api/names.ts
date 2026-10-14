/**
 * The artists and promoters an event names, by name. `GET /api/admin/artists?name=` and
 * `GET /api/admin/promoters?name=` match any part of the name, ignoring case (#2988).
 */

import { failOnError } from '@/api/events'

/** An artist or a promoter as a picker shows it. */
export interface NamedRow {
  id: number
  name: string
  slug: string
}

export type NameKind = 'artists' | 'promoters'

/** How many matches a search shows. More means the name typed is too short. */
export const SEARCH_LIMIT = 10

interface Page<T> {
  content: T[]
  totalElements: number
}

export interface SearchResult {
  rows: NamedRow[]
  total: number
}

async function getJson<T>(path: string, fetchFn: typeof fetch): Promise<T> {
  const response = await fetchFn(path, { headers: { Accept: 'application/json' } })
  if (!response.ok) throw new Error(`GET ${path}: HTTP ${response.status}`)
  return (await response.json()) as T
}

/** The first [SEARCH_LIMIT] rows of [kind] whose name contains [name], sorted by name. */
export async function searchByName(
  kind: NameKind,
  name: string,
  fetchFn: typeof fetch = fetch,
): Promise<SearchResult> {
  const query = new URLSearchParams({ name: name.trim(), size: String(SEARCH_LIMIT), sort: 'name' })
  const page = await getJson<Page<NamedRow>>(`/api/admin/${kind}?${query}`, fetchFn)
  return {
    rows: page.content.map(({ id, name, slug }) => ({ id, name, slug })),
    total: page.totalElements,
  }
}

/**
 * The names of the [ids] of [kind], one GET each: the event answer carries ids only, and a lineup
 * is a handful of rows. An id that fails to load maps to null, so one missing row does not hide
 * the others.
 */
export async function fetchNames(
  kind: NameKind,
  ids: number[],
  fetchFn: typeof fetch = fetch,
): Promise<Map<number, NamedRow | null>> {
  const unique = [...new Set(ids)]
  const rows = await Promise.all(
    unique.map((id) => getJson<NamedRow>(`/api/admin/${kind}/${id}`, fetchFn).catch(() => null)),
  )
  return new Map(unique.map((id, i) => [id, rows[i] ?? null]))
}

/**
 * The artist named exactly [name], ignoring case, or null. The search matches any part of the name,
 * so it reads a page of 100 and keeps the exact match.
 */
export async function findArtistByName(
  name: string,
  fetchFn: typeof fetch = fetch,
): Promise<NamedRow | null> {
  const wanted = name.trim()
  const query = new URLSearchParams({ name: wanted, size: '100', sort: 'name' })
  const page = await getJson<Page<NamedRow>>(`/api/admin/artists?${query}`, fetchFn)
  const found = page.content.find((row) => row.name.toLowerCase() === wanted.toLowerCase())
  return found ? { id: found.id, name: found.name, slug: found.slug } : null
}

/** Creates an artist with [name] and nothing else. The importer answers 409 when its slug is taken. */
export async function createArtist(name: string, fetchFn: typeof fetch = fetch): Promise<NamedRow> {
  const response = await fetchFn('/api/admin/artists', {
    method: 'POST',
    headers: { Accept: 'application/json', 'Content-Type': 'application/json' },
    body: JSON.stringify({ name: name.trim() }),
  })
  await failOnError(response, 'POST /api/admin/artists')
  const { id, name: stored, slug } = (await response.json()) as NamedRow
  return { id, name: stored, slug }
}
