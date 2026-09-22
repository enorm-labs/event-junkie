import process from 'node:process'
import { defineConfig, devices } from '@playwright/test'

/**
 * The Ingress name the chart routes, and where k3d publishes Traefik (#1699). `Host` cannot be set on
 * a navigation, so chromium is told to resolve that name to the published port instead — which is
 * also what makes the request carry the right `Host`, SNI included.
 */
const REAL_DATA_URL = process.env.E2E_BASE_URL ?? 'http://event-junkie.localhost:8080'

/** The one suite that talks to a deployment, kept out of every other project by path. */
const REAL_DATA_DIR = '**/real-data/**'

/**
 * The deployment suite (#1699): the chart on k3d, through Traefik, against the rows
 * `fixtures/events.sql` seeded. It mocks nothing — that is the point, and `e2e/real-data/` is a
 * directory rather than a naming convention so the rule is a path a job can check.
 *
 * **Rendered only when `E2E_BASE_URL` is set**, because a bare `npm run test:e2e` runs every project:
 * on the frontend runner there is no cluster, so an unconditional project turns that suite red with
 * 17 connection failures. Setting the variable is what opts in, and `e2e-k3d` in `dast.yml` sets it.
 *
 * Chromium alone: what is under test is nginx, the middlewares and a real BFF, not how five engines
 * lay the page out. `--host-resolver-rules` is what lets the browser ask for the Ingress name — a
 * `Host` header cannot be set on a navigation.
 */
function realDataProject() {
  if (!process.env.E2E_BASE_URL) return []
  const url = new URL(REAL_DATA_URL)
  return [
    {
      name: 'real-data',
      testDir: './e2e/real-data',
      use: {
        ...devices['Desktop Chrome'],
        baseURL: REAL_DATA_URL,
        launchOptions: {
          args: [`--host-resolver-rules=MAP ${url.hostname} 127.0.0.1:${url.port || 80}`],
        },
      },
    },
  ]
}

export default defineConfig({
  testDir: './e2e',
  timeout: 30 * 1000,
  expect: {
    timeout: 5000,
  },
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  /**
   * Two workers on CI, one browser per pair of the runner's four vCPUs. Nothing needs serialising
   * (every data-driven test mocks the BFF through its own `page.route`), and measured on the runner
   * over all 1,075 tests: 628s on one worker, 454s on four with a flake, 334s on two. A local run
   * keeps Playwright's default of half the cores.
   */
  workers: process.env.CI ? 2 : undefined,
  reporter: 'html',
  use: {
    actionTimeout: 0,
    baseURL: process.env.CI ? 'http://localhost:4173' : 'http://localhost:5173',
    trace: 'on-first-retry',
    headless: !!process.env.CI,
  },

  projects: [
    {
      // The five browser projects run against a dev server with no BFF, so none of them may pick up
      // `real-data/`. A top-level `testIgnore` would also apply to the project whose testDir is in
      // there, and Playwright would then find no tests at all.
      testIgnore: REAL_DATA_DIR,
      name: 'chromium',
      use: {
        ...devices['Desktop Chrome'],
      },
    },
    {
      testIgnore: REAL_DATA_DIR,
      name: 'firefox',
      use: {
        ...devices['Desktop Firefox'],
      },
    },
    {
      testIgnore: REAL_DATA_DIR,
      name: 'webkit',
      use: {
        ...devices['Desktop Safari'],
      },
    },

    {
      testIgnore: REAL_DATA_DIR,
      name: 'Mobile Chrome',
      use: {
        ...devices['Pixel 5'],
      },
    },
    {
      testIgnore: REAL_DATA_DIR,
      name: 'Mobile Safari',
      use: {
        ...devices['iPhone 12'],
      },
    },

    ...realDataProject(),
  ],

  // Not started for the `real-data` project: it has a deployment to talk to, and a Vite server on
  // 4173 would answer nothing it asks for. Playwright starts this for every run, so the project is
  // selected with `--project=real-data` and the server is harmless; `E2E_BASE_URL` decides the target.
  webServer: {
    /**
     * Dev server locally for the feedback loop, preview server on CI; an already running dev server
     * is reused.
     */
    command: process.env.CI ? 'npm run preview' : 'npm run dev',
    port: process.env.CI ? 4173 : 5173,
    reuseExistingServer: !process.env.CI,
  },
})
