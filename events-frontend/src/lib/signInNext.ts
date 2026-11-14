/** What the page a sign-in returns to does at once, as the visitor's press would have (#4969). */
export const SIGN_IN_ACTIONS = ['follow', 'rsvp-going', 'rsvp-interested', 'save'] as const

export type SignInAction = (typeof SIGN_IN_ACTIONS)[number]

/** The keys `savedQuery` writes, and the list's own dates, so the list shows what the visitor saw. */
const FILTER_KEYS = [
  'q',
  'eventType',
  'venue',
  'district',
  'venueType',
  'genre',
  'family',
  'minPrice',
  'maxPrice',
  'excludeSoldOut',
  'free',
  'timeOfDay',
  'language',
  'feature',
  'festival',
  'when',
  'from',
  'to',
]
// A value as `URLSearchParams` encodes it. The cap leaves room for a search the BFF saves (1000).
const PARAM = `(do=(${SIGN_IN_ACTIONS.join('|')})|(${FILTER_KEYS.join('|')})=[A-Za-z0-9._~%*+-]+)`

/**
 * The page a sign-in returns to (#3889): a path of this site, as the BFF also checks, or null. A
 * query value can be anything a link put there, so nothing else is ever followed. The path may
 * carry a closed query (#4969): the action and the filters of a search to save. The BFF's
 * `SignInNext.kt` is the same pattern.
 */
const NEXT = new RegExp(`^/(de|en)/[a-z0-9/-]{0,200}(\\?(?=.{1,1100}$)${PARAM}(&${PARAM})*)?$`)

export function signInNext(value: unknown): string | null {
  return typeof value === 'string' && NEXT.test(value) ? value : null
}

/**
 * The `next` for [path] that also does [action], with the list's [query] for a search to save.
 * Too long a search returns to the bare path, as before #4969, so the link never breaks.
 */
export function signInReturn(
  path: string,
  action: SignInAction,
  query: URLSearchParams = new URLSearchParams(),
): string {
  const params = new URLSearchParams(query)
  params.set('do', action)
  return signInNext(`${path}?${params}`) ?? path
}

/** The action a query value asks for, or null. */
export function signInAction(value: unknown): SignInAction | null {
  return SIGN_IN_ACTIONS.find((action) => action === value) ?? null
}
