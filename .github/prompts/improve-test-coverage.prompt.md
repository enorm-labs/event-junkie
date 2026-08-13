# Improve Test Coverage

Analyze test coverage reports, identify under-tested areas, and add missing tests to improve coverage.

## Important

Always run git commands with the pager disabled (`git --no-pager ...`) to prevent hanging on interactive output.

---

## Backend (Kotlin — Kover)

### 1. Generate the Coverage Report

Run Kover to get the current coverage baseline:

```bash
./gradlew koverLog
```

This prints a per-module line/branch coverage summary to the console. For a detailed breakdown by class and method, generate the HTML report:

```bash
./gradlew koverHtmlReport
```

The HTML report is written to `build/reports/kover/html/index.html`. Open it to inspect per-file coverage.

### 2. Identify Coverage Gaps

Review the Kover output and identify classes/methods with low or missing coverage. Prioritize:

1. **Business logic in `@Service` classes** — these contain domain rules and should have the highest coverage.
2. **Controller endpoints** — verify HTTP status codes, request validation, and error responses.
3. **Entity ↔ domain conversion** — `toDomain()` / `fromDomain()` / `fromEntity()` factory methods.
4. **Edge cases** — null handling, empty collections, validation failures, exception paths.
5. **Scraper logic** — `*OverviewPageScraper` and `*DetailPageScraper` classes that parse HTML.

Deprioritize:

- Auto-generated code, configuration classes, and Spring Boot application entry points.
- `*Module.kt` marker classes (just annotations, no logic).
- Simple getters/setters with no logic.

### 3. Read Existing Tests

Before writing new tests, read the existing test files for the module you're targeting. Understand:

- Which scenarios are already covered.
- The testing style and patterns in use (see conventions below).
- Fixture factories available in `*RequestFixtures` objects.

### 4. Add Missing Tests

Write tests that target the identified coverage gaps. Follow the project's testing conventions strictly.

### 5. Verify

After adding tests, run the full build to ensure everything compiles and passes:

```bash
./gradlew clean build
```

Then re-run coverage to confirm improvement:

```bash
./gradlew koverLog
```

If ktlint reports formatting issues, auto-fix first:

```bash
./gradlew ktlintFormat
```

## Testing Conventions

Follow these patterns — they are established in the codebase and must be respected for consistency.

### General

- **JUnit 5** as the test framework.
- **Backtick function names** for readable test descriptions: `` `should return 404 when venue not found`() ``.
- **Kotest assertions** (`shouldBe`, `shouldContain`, `shouldHaveSize`, `shouldNotBeNull`) — not JUnit `assertEquals`.
- **MockK** for mocking — not Mockito. Use `mockk<T>()`, `every { }`, `coEvery { }`, `verify { }`, `coVerify { }`.
- Use `runTest` for testing coroutines (from `kotlinx-coroutines-test`).

### Integration Tests (importer)

- Extend `BaseControllerTest` — it provides Testcontainers PostgreSQL, `WebTestClient`, and `@BeforeEach` table truncation.
- Use `WebTestClient` for HTTP assertions:
    ```kotlin
    webTestClient.post().uri("/api/admin/venues")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(request)
        .exchange()
        .expectStatus().isCreated
        .expectBody<VenueResponse>()
        .returnResult().responseBody!!
    ```
- Test the full HTTP request/response cycle: correct status codes, response bodies, validation errors, and error responses.

### Unit Tests

- Test services in isolation by mocking repository dependencies with MockK.
- Test both happy path and error/edge cases.
- For scraper tests: load HTML from test resource files and verify parsed output.

### Test Fixture Factories

- Use existing `*RequestFixtures` object singletons (e.g., `VenueRequestFixtures.create(name = "Custom")`) to construct test data with sensible defaults. Only
  override properties relevant to the specific test scenario.
- If a fixture factory doesn't exist for the entity you're testing, create one following the same pattern.

### File Naming & Placement

- Test files mirror the source structure: `src/test/kotlin/de/norm/events/<module>/<ClassNameTest>.kt`.
- Integration tests sit alongside unit tests in the same package.

---

## Frontend (TypeScript/Vue — Vitest Coverage)

### 1. Generate the Coverage Report

Run unit tests with coverage enabled:

```bash
npm run test:unit:coverage
```

This prints a summary to the console and generates an HTML report in `coverage/index.html`. Uses `@vitest/coverage-v8` (V8's native code coverage — already
installed as a dev dependency).

### 2. Identify Coverage Gaps

Prioritize:

1. **Composables** (`src/composables/`) — reusable logic functions are easy to unit test.
2. **Utility/helper functions** — pure functions with no component dependencies.
3. **Components with complex logic** — computed properties, watchers, event handlers.

Deprioritize:

- Pure template/layout components with no logic.
- Router configuration and app entry point (`main.ts`).
- Third-party library wrappers with trivial pass-through.

### 3. Add Missing Tests

Follow the frontend testing conventions:

- **Vitest** as the test framework with jsdom environment.
- **`@vue/test-utils`** for component mounting and interaction.
- Test files colocated: `src/components/__tests__/*.spec.ts` or alongside composables.
- Use `data-testid` attributes for stable element selectors.
- Test composables in isolation — call the function, assert on returned refs/computed values.
- Test store actions and getters independently (no component mount needed).

### 4. Verify

```bash
npm run build                # Type-check + build
npm run test:unit -- --run   # Run tests (single run, no watch)
npm run test:unit:coverage   # Confirm coverage improvement
```

## Output

After completing the changes, provide a summary of:

1. **Before**: per-module coverage numbers from the initial run (backend: `koverLog`, frontend: `vitest --coverage`).
2. **After**: per-module coverage numbers from the final run.
3. **Tests added**: list of new test classes/methods and what they cover.
4. **Remaining gaps**: any known low-coverage areas that were intentionally skipped (with reasons).
