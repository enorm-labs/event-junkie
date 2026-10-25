/**
 * The page a sign-in returns to (#3889): a path of this site, as the BFF also checks, or null. A
 * query value can be anything a link put there, so nothing else is ever followed.
 */
const NEXT = /^\/(de|en)\/[a-z0-9/-]{0,200}$/

export function signInNext(value: unknown): string | null {
  return typeof value === 'string' && NEXT.test(value) ? value : null
}
