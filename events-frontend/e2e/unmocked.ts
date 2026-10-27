import { expect, type Route, test } from '@playwright/test'

/**
 * The last answer of a spec's `/api/(auth|me)` mock (#4095). A write gets the 204 the BFF answers.
 * A GET gets a 501 and fails the test by its path: a bare 204 is a body the BFF never sends, and the
 * page then breaks somewhere else, late, and only on some runs.
 */
export function failUnmockedGets(): (route: Route) => Promise<void> {
  let unmocked: string[] = []
  test.beforeEach(() => {
    unmocked = []
  })
  test.afterEach(() => {
    expect(unmocked, `GETs with no mock: ${unmocked.join(', ')}`).toEqual([])
  })
  return (route) => {
    const request = route.request()
    if (request.method() !== 'GET') return route.fulfill({ status: 204 })
    const path = new URL(request.url()).pathname
    unmocked.push(path)
    return route.fulfill({ status: 501, body: path })
  }
}
