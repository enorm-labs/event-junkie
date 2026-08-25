---
applyTo: "**/src/test/**,**/src/testFixtures/**"
paths:
    - "**/src/test/**"
    - "**/src/testFixtures/**"
---

# Backend Testing Patterns

Extend what is already here rather than repeating its boilerplate. The frontend's testing conventions live in
[events-frontend/AGENTS.md](../../events-frontend/AGENTS.md).

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
- Testcontainers use `PostgreSQLContainer("postgres:18.3-alpine").withReuse(true)` to match the dev compose image and speed up repeated test runs. Uses modular
  Testcontainers 2.x artifacts (`org.testcontainers:testcontainers-postgresql`, `testcontainers-r2dbc`, `testcontainers-junit-jupiter`)
  with modular package imports (`org.testcontainers.postgresql.PostgreSQLContainer`).
- Use backtick function names for readable test descriptions: `` `GET hello returns Hello world`() ``.
- **BaseControllerTest** (importer only): Abstract base class for integration tests that extends Testcontainers setup, provides a `WebTestClient`, and truncates
  all domain tables via `@BeforeEach` so each test starts with a clean database. Extend this instead of repeating boilerplate.
- **Kotest assertions**: The importer uses `io.kotest:kotest-assertions-core` for expressive test matchers (e.g. `shouldBe`, `shouldContain`).
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
