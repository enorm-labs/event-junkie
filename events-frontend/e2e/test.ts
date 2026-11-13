import { test as base } from '@playwright/test'

export { expect } from '@playwright/test'
export type { BrowserContext, Locator, Page, Route } from '@playwright/test'

type Violation = { directive: string; blocked: string; sample: string; source: string; page: string }

/**
 * Playwright's `test`, failing any test whose pages break the Content-Security-Policy (#4955). It stands in for a
 * report endpoint: a dependency that starts writing `innerHTML` fails CI instead of production. Only the preview
 * server sends the policy, so it bites under `CI=1`, and Chromium is the browser that enforces Trusted Types.
 */
export const test = base.extend<{ cspViolations: Violation[] }>({
  cspViolations: [
    async ({ page }, use) => {
      const violations: Violation[] = []
      await page.exposeBinding('__cspViolation', (_source, violation: Violation) => {
        violations.push(violation)
      })
      await page.addInitScript(() => {
        document.addEventListener('securitypolicyviolation', (e) => {
          // WebKit reports a `<select>` it renders itself, with no inline style of ours, as a `style-src-attr` violation.
          if (e.effectiveDirective === 'style-src-attr' && e.target instanceof HTMLSelectElement) return
          void (window as unknown as { __cspViolation: (v: Violation) => Promise<void> }).__cspViolation({
            directive: e.effectiveDirective,
            blocked: e.blockedURI,
            sample: e.sample,
            source: `${e.sourceFile}:${e.lineNumber}:${e.columnNumber}`,
            page: location.pathname,
          })
        })
      })
      await use(violations)
      if (violations.length > 0) {
        throw new Error(`Content-Security-Policy violations:\n${JSON.stringify(violations, null, 2)}`)
      }
    },
    { auto: true },
  ],
})
