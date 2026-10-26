// A saved search (#3890) is the events list's query string. A date preset is kept as `when`, so
// "this weekend" is this weekend whenever the search is opened; a hand-picked range is not kept.
import type { EventSearchParams } from '@/composables/useEvents'
import { activePresetKey, DATE_PRESETS } from './dateRanges'

type FilterValue = string | number | boolean | readonly string[] | undefined

const WHEN_BY_PRESET: Readonly<Record<string, string>> = {
  'dateRange.today': 'today',
  'dateRange.thisWeekend': 'weekend',
  'dateRange.next7Days': 'week',
}
const PRESET_BY_WHEN = Object.fromEntries(
  Object.entries(WHEN_BY_PRESET).map(([preset, when]) => [when, preset]),
)

/** The filters a visitor picks from lists, sent as repeated parameters. */
const LIST_KEYS = new Set([
  'eventType',
  'venue',
  'district',
  'venueType',
  'genre',
  'family',
  'timeOfDay',
  'language',
  'feature',
])
const NUMBER_KEYS = new Set(['minPrice', 'maxPrice'])
const BOOLEAN_KEYS = new Set(['excludeSoldOut', 'free'])

/**
 * The query to save for the list's [filters] and [range], or null when nothing narrows the list.
 * `datesDropped` says a hand-picked range was left out, so the form can say so.
 */
export function savedQuery(
  filters: Readonly<Record<string, FilterValue>>,
  range: { from?: string; to?: string },
): { query: string; datesDropped: boolean } | null {
  const query = new URLSearchParams()
  for (const [name, value] of Object.entries(filters)) {
    for (const item of Array.isArray(value) ? value : [value]) {
      if (item !== undefined && item !== '' && item !== false) query.append(name, String(item))
    }
  }
  let datesDropped = false
  if (range.from || range.to) {
    const preset =
      range.from && range.to ? activePresetKey({ from: range.from, to: range.to }) : undefined
    const when = preset && WHEN_BY_PRESET[preset]
    if (when) query.append('when', when)
    else datesDropped = true
  }
  return query.size ? { query: query.toString(), datesDropped } : null
}

/** The list's route query for a saved [query], with `when` turned into today's `from` and `to`. */
export function listQuery(query: string): Record<string, string | string[]> {
  const out: Record<string, string | string[]> = {}
  for (const [name, value] of new URLSearchParams(query)) {
    if (name === 'when') {
      const preset = DATE_PRESETS.find((p) => p.key === PRESET_BY_WHEN[value])
      if (preset) Object.assign(out, preset.range())
    } else if (LIST_KEYS.has(name)) {
      out[name] = [...((out[name] as string[] | undefined) ?? []), value]
    } else {
      out[name] = value
    }
  }
  return out
}

/** The same search as API parameters, for counting its matches. */
export function searchParams(query: string): EventSearchParams {
  const params: Record<string, unknown> = {}
  for (const [name, value] of Object.entries(listQuery(query))) {
    if (NUMBER_KEYS.has(name)) params[name] = Number(value)
    else if (BOOLEAN_KEYS.has(name)) params[name] = value === 'true'
    else params[name] = value
  }
  if (params.from) params.running = true
  return params as EventSearchParams
}

/** The one genre or district a search holds and nothing else, which a single press saves. */
export function singleFilter(
  filters: Readonly<Record<string, FilterValue>>,
  range: { from?: string; to?: string },
): { key: 'genre' | 'district'; value: string } | null {
  if (range.from || range.to) return null
  const set = Object.entries(filters).filter(
    ([, value]) => value !== undefined && value !== '' && value !== false,
  )
  if (set.length !== 1) return null
  const [key, value] = set[0]!
  if ((key === 'genre' || key === 'district') && Array.isArray(value) && value.length === 1)
    return { key, value: value[0]! }
  return null
}
