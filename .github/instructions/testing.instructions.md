---
applyTo: "**/src/test/**,**/src/testFixtures/**,events-frontend/e2e/**,events-frontend/**/__tests__/**"
paths:
    - "**/src/test/**"
    - "**/src/testFixtures/**"
    - "events-frontend/e2e/**"
    - "events-frontend/**/__tests__/**"
---

# Testing Patterns

Extend what is already here rather than repeating its boilerplate.

## Backend (JUnit, WebTestClient, Testcontainers)

- **JUnit 5 lifecycle, Kotest assertions, in every module** (#946): `io.kotest:kotest-assertions-core` is the one assertion library — no JUnit `Assertions`,
  AssertJ or `kotlin.test`; four libraries once meant a test that failed to _compile_ across a module boundary. Kotest is never the runner. A failure message
  goes in `withClue("…") { … }`; `assertSoftly` replaces `assertAll`. Backtick test names. MockK for mocks.
- **`BaseControllerTest`** (importer) extends the Testcontainers setup, provides a `WebTestClient` bound to `@LocalServerPort`, and truncates every domain
  table in `@BeforeEach`; extend it. Each Boot module has its own `PostgresTestcontainersConfiguration` (`@ServiceConnection`, `postgres:18.3-alpine`, the
  compose image). **Reuse is deliberately off** (#954): 13 seconds locally, nothing in CI, and every context would share one database. Each runtime starter
  gets its `-test` companion. `events-core` publishes fixtures via `java-test-fixtures` (`testImplementation(testFixtures(project(":events-core")))`); each
  importer feature has a `*RequestFixtures` object with defaults.
- **Every distinct test-context configuration costs a cached Spring context, a container and an R2DBC pool for the whole task.** Count them with
  `./gradlew :events-importer:test --rerun-tasks 2>&1 | grep -c 'Commencing graceful shutdown'` (2 for the importer, 3 for the BFF since #965). A
  `@TestPropertySource`, `@Import`, `@AutoConfigureMetrics` or different `webEnvironment` on a class forks one: put it on `BaseControllerTest`, or say in the
  KDoc why the class needs its own (`EventImportServiceIntegrationTest` does).
- **Nothing scheduled may run in a backend test, and two switches are needed** (#949): `app.scheduling.enabled: false` for the importer's own
  `@EnableScheduling`, and `spring.modulith.moments.enabled: false`, because `spring-modulith-moments` carries its own and registers the
  `ScheduledAnnotationBeanPostProcessor` regardless. **Assert the effect, never the switch**: `SchedulingDisabledInTestsTest` asserts no such bean exists;
  asserting the property passed for hours while the suite deadlocked. **A long interval does not disable a task** — `fixedDelayString` has no `initialDelay`,
  so the first run fires at context refresh, raced `TRUNCATE` in `cleanUp`, and one arbitrary test per run died on `40P01 deadlock_detected` (#934).
- **MockWebServer** (`ApiClientTest`, `HtmlFetcherTest`) drives the real `WebClient` pipeline: the `com.squareup.okhttp3:mockwebserver3` artifact, never the
  legacy `mockwebserver`, whose `MockWebServer` extends JUnit 4's `ExternalResource` and drags `junit:junit` in. `MockResponse.Builder()`, `close()` not
  `shutdown()`, `RecordedRequest.target` (includes the query string).
- `FullLifecycleIntegrationTest` walks create → list → get → update → delete across every entity, mirroring `full-lifecycle.http`; extend it for a new
  cross-entity flow. `ModularityTests` in each module verify Modulith structure and write docs to `build/spring-modulith-docs/`.

## Frontend (Vitest, Playwright)

- **Unit**: colocated `__tests__/*.spec.ts`, jsdom, `@vue/test-utils`, `data-testid` selectors, composables tested by calling them. `npm run test:unit`
  (watch), `-- --run`, `test:unit:coverage`.
- **e2e**: `e2e/*.spec.ts` over **five projects** — Chromium, Firefox, WebKit, Mobile Chrome (Pixel 5), Mobile Safari (iPhone 12), the last two ~390 px.
  Dev mode reuses `:5173`; `CI=1` builds and serves `:4173`. CI runs the full matrix; `/verify` runs chromium only.
- **Locale strategy: every suite is pinned to `/en` except `e2e/i18n.spec.ts` and the axe sweep.** The others are behaviour tests using English accessible
  names as stable handles — and landmark names are translated, so `getByRole('navigation', { name: 'Main' })` is `'Haupt'` under `/de`. Anything that exists
  only in a second language (the URL contract, the switcher, date formats, the per-locale pages) goes in `i18n.spec.ts`. The axe sweep runs both locales
  because German is longer, which is where overflow and contrast regress.
- **Two kinds of e2e spec, and the directory is the rule.** `e2e/*.spec.ts` mock the BFF with
  `page.route` and run against a dev server; they cover component behaviour and gate every pull request.
  `e2e/real-data/*.spec.ts` mock nothing and run only against a deployment: the chart on k3d, nightly,
  from `e2e-k3d` in `dast.yml` (#1699). **The project exists only when `E2E_BASE_URL` is set**, or a
  bare `npm run test:e2e` would run it against a runner with no cluster behind it. A spec belongs there
  only when it asserts something a deployment has and a dev server does not — nginx serving the bundle,
  Traefik's middlewares and the CSP, the SPA fallback, the rows `fixtures/events.sql` seeded (#272).
  **A `page.route` under `real-data/` fails `npm run lint`** (`no-restricted-syntax`, scoped to that
  directory), because it would put a mock back in front of the thing under test. Chromium only there: what is under test is the deployment, not five engines.
- **`/verify` is chromium desktop and will not see a mobile break** — a header nav overflowing 390 px pushed a control off-screen once. When touching the
  app shell, header, nav or any layout: `npm run test:e2e -- --project="Mobile Chrome" --project="Mobile Safari"` before pushing. On CI such a break also
  burns 30 s × 2 retries × 5 projects.
