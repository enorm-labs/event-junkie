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

- **JUnit 5** + **WebTestClient** for reactive endpoint tests (see `BaseControllerTest.kt`). Create the client via lazy delegate with `@LocalServerPort`:
    ```kotlin
    @LocalServerPort private var port: Int = 0
    private val webTestClient: WebTestClient by lazy {
        WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }
    ```
- **Spring Boot 4 test starters**: Each runtime starter has a `*-test` companion (e.g. `spring-boot-starter-webflux-test`,
  `spring-boot-starter-data-r2dbc-test`). Always add the `-test` variant alongside the main starter.
- Tests requiring PostgreSQL import `PostgresTestcontainersConfiguration` via `@Import` — this provides a reusable Testcontainers `@ServiceConnection` bean.
  Both `events-bff` and `events-importer` have their own copy.
- Testcontainers use `PostgreSQLContainer("postgres:18.3-alpine")` to match the dev compose image. **Reuse is deliberately not enabled (#954):** it saved about
  13 seconds across a full backend cycle locally and nothing in CI, and every context builds an identical container, so they would all share one database.
  Uses modular
  Testcontainers 2.x artifacts (`org.testcontainers:testcontainers-postgresql`, `testcontainers-r2dbc`, `testcontainers-junit-jupiter`)
  with modular package imports (`org.testcontainers.postgresql.PostgreSQLContainer`).
- Use backtick function names for readable test descriptions: `` `GET hello returns Hello world`() ``.
- **Every distinct test-context configuration costs a cached Spring context, a PostgreSQL container and an R2DBC pool, for the whole test task.** The count
  today is **2 for `events-importer` and 3 for `events-bff`**, down from 5 and 4 (#965). Count them with
  `./gradlew :events-importer:test --rerun-tasks 2>&1 | grep -c 'Commencing graceful shutdown'`, which counts `RANDOM_PORT` contexts without instrumenting
  anything. **A change to that number is a real change** — a `@TestPropertySource`, an `@Import`, an `@AutoConfigureMetrics` or a different `webEnvironment` on
  a test class all fork one. Put the annotation on `BaseControllerTest` where the whole suite can share it, or say in the KDoc why this class needs its own.
  `EventImportServiceIntegrationTest` is the one that kept its fork, and its KDoc says why.
- **BaseControllerTest** (importer only): Abstract base class for integration tests that extends Testcontainers setup, provides a `WebTestClient`, and truncates
  all domain tables via `@BeforeEach` so each test starts with a clean database. Extend this instead of repeating boilerplate.
- **Nothing scheduled may run in a backend test, and two separate things are needed to get that (#949).** `SchedulingConfiguration` in the importer carries
  `@EnableScheduling` behind `app.scheduling.enabled`, and the test `application.yaml` also sets `spring.modulith.moments.enabled: false`. The second one is
  not optional: `spring-modulith-moments` carries its own `@EnableScheduling`, so a dependency registers the `ScheduledAnnotationBeanPostProcessor` whatever
  this application asks for, and every `@Scheduled` method keeps firing with the switch off.
- **Assert the effect, never the switch.** `SchedulingDisabledInTestsTest` asserts that no `ScheduledAnnotationBeanPostProcessor` bean exists. A test that
  asserted the condition instead passed for hours while the suite still deadlocked. Any dependency that starts enabling scheduling fails there.
- **A long interval does not disable a scheduled task.** `fixedDelayString` carries no `initialDelay`, so the first execution runs at context refresh whatever
  the interval says. #934 set an interval of one hour, and each cached Spring context still fired every gauge refresher once on startup. Those queries raced
  the `TRUNCATE` in `BaseControllerTest.cleanUp`, taking the same tables in the opposite order, and one arbitrary test per run died on `40P01
deadlock_detected`.
- **Kotest assertions, in every module**: `io.kotest:kotest-assertions-core` is the one assertion library here (#946) — `shouldBe`, `shouldContain`,
  `shouldHaveSize` and the rest. It is declared in all four `build.gradle.kts` files, versioned from `kotest.version`. Do not add JUnit's `Assertions`,
  AssertJ or `kotlin.test` assertions back: the modules held four different libraries until #946, and the tax was a test that failed to _compile_ when
  its author crossed a module boundary. The JUnit **lifecycle** (`@Test`, `@Nested`, `@BeforeEach`, `@ParameterizedTest`) stays — Kotest is used here for
  assertions only, never as the runner.
- **Carry a failure message across as `withClue`**, not as a dropped argument. JUnit and AssertJ take a message; `shouldBe` does not, so an assertion
  whose message explains _why_ the invariant exists wraps in `withClue("…") { … }`. Group several assertions with `assertSoftly` so one failure does not
  hide the rest — it is the replacement for `assertAll`.
- **MockK**: The importer uses `io.mockk:mockk` for mocking in Kotlin tests (preferred over Mockito). Used for unit-testing services with injected dependencies.
- **MockWebServer**: `ApiClientTest` and `HtmlFetcherTest` drive the real `WebClient` pipeline against a local server rather than mocking HTTP. Use the **
  `com.squareup.okhttp3:mockwebserver3`** artifact (package `mockwebserver3`), _not_ the legacy `com.squareup.okhttp3:mockwebserver` — the latter still ships at
  5.x purely as a deprecation bridge whose `MockWebServer` extends JUnit 4's `ExternalResource`, which would put `junit:junit` back on the classpath of this
  JUnit 5-only project. API notes: `MockResponse` is immutable (`MockResponse.Builder().code(…).body(…).build()`), the server is closed with `close()` rather
  than `shutdown()`, and the recorded request line is `RecordedRequest.target` (the okhttp 4 `path` property is gone; `target` includes the query string, so it
  is a drop-in replacement).
- **Test fixture factories**: Each importer feature module has a `*RequestFixtures` object singleton with factory methods that provide sensible defaults, so
  tests only override properties relevant to the scenario (e.g. `VenueRequestFixtures.astra()`, `VenueRequestFixtures.create(name = "Privatclub")`).
- **Full lifecycle integration test**: `FullLifecycleIntegrationTest` exercises the complete CRUD flow across all entity types in a single sequential scenario
  (create → list → get → update → delete), mirroring the `full-lifecycle.http` script. Extend this pattern for new cross-entity workflows.
- `ModularityTests` in each module (core, BFF, importer) validates Spring Modulith structure and generates docs to `build/spring-modulith-docs/`.
- `events-core` publishes test fixtures via `java-test-fixtures` plugin — consume with `testImplementation(testFixtures(project(":events-core")))`.

## Frontend (Vitest, Playwright)

### Unit tests (Vitest)

- Test files are colocated with components: `src/components/__tests__/*.spec.ts`.
- Uses **jsdom** as the DOM environment.
- Use `@vue/test-utils` for component mounting and interaction.
- Use **`data-testid` attributes** for test selectors — decoupled from CSS classes and DOM structure.
- Test composables in isolation (no component mount needed — just call the function and assert on returned refs).
- Run with: `npm run test:unit` (watch mode) or `npm run test:unit -- --run` (single run).
- Run with coverage: `npm run test:unit:coverage` — prints summary to console and generates HTML report in `coverage/`.

### End-to-end tests (Playwright)

- Test files live in `e2e/` directory with `*.spec.ts` extension.
- Tests run against **five projects**: Desktop Chromium, Firefox, WebKit, plus **Mobile Chrome (Pixel 5)
  and Mobile Safari (iPhone 12)** — the last two use ~390px viewports.
- Dev mode: runs against `http://localhost:5173` (Vite dev server, reuses existing).
- CI mode: builds first, then runs against `http://localhost:4173` (Vite preview server).
- Run with: `npm run test:e2e`. CI runs the **full matrix**; the `/verify` skill runs **chromium only** to stay fast.
- **Locale strategy: every suite is pinned to `/en` except `e2e/i18n.spec.ts` and the axe sweep.** The other suites are behaviour tests that happen to use
  English accessible names as stable handles; re-running them in German would double an already five-project matrix to re-assert the same behaviour. So put
  anything that only exists in a second language — the URL contract, the switcher, date formats, the per-locale pages — in `i18n.spec.ts`, and leave the rest
  in English.
    - **Two exceptions, both deliberate.** The **axe sweep runs both locales**, because German is reliably longer and that is where a layout overflow or a
      contrast regression actually appears. And **landmark names are translated**, so a selector like `getByRole('navigation', { name: 'Main' })` becomes
      `'Haupt'` under `/de` — which is the concrete reason the other suites stay on `/en` rather than a stylistic one.
- **Layout/responsive gotcha:** because `/verify` is chromium-only (desktop viewport), it will not catch
  regressions that only appear on the mobile projects — e.g. a wider header nav overflowing a ~390px screen
  and pushing a control off-screen (a real failure we hit). When touching the **app shell, header/nav, or any
  layout**, run the mobile projects locally before pushing:
  `npm run test:e2e -- --project="Mobile Chrome" --project="Mobile Safari"`. On CI such a break also _slows_
  the run — a failing interaction burns the 30s action timeout × 2 retries × 5 projects.
