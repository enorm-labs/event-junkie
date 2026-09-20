# Improve Test Coverage

Read the coverage reports, find the under-tested code that matters, and write the tests. `git --no-pager` on every git command.

## Backend (Kover)

1. **Baseline**: `./gradlew koverLog` (per module), `./gradlew koverHtmlReport` (per class, `build/reports/kover/html/index.html`).
2. **Prioritise**: `@Service` business logic → controller endpoints (status codes, validation, error bodies) → `toDomain()` / `fromDomain()` / `fromEntity()`
   → edge cases (null, empty, validation failure, exception paths) → scraper parsers against their fixtures. Skip configuration classes, entry points,
   `*Module.kt` markers and logic-free accessors — Kover already excludes most of them.
3. **Read the module's existing tests first**: what is covered, the style, the `*RequestFixtures` available. New tests look like their neighbours;
   [testing.instructions.md](../instructions/testing.instructions.md) loads with them and is the convention — `BaseControllerTest` for integration,
   `WebTestClient` for the full cycle, MockK for isolation, Kotest assertions, backtick names, `runTest` for coroutines, a fixture factory per entity (create
   one in the same pattern if missing). Test files mirror the source path.
4. **A new test class must not fork a Spring context.** No `@TestPropertySource` or `@Import` on a class that `BaseControllerTest` could carry instead;
   the context count is measured, and a new one costs a container for the whole task.
5. **Verify**: `./gradlew ktlintFormat`, then `./gradlew clean build`, then `koverLog` again for the after number.

## Frontend (Vitest)

1. **Baseline**: `npm run test:unit:coverage` (console summary, `coverage/index.html`).
2. **Prioritise**: composables → pure helpers in `src/lib/` → components with real logic (computed, watchers, handlers). Skip layout-only components, the
   router and `main.ts`.
3. Colocated `__tests__/*.spec.ts`, `@vue/test-utils`, `data-testid` selectors, composables tested by calling them.
4. **Verify**: `npm run build`, `npm run test:unit -- --run`, `npm run test:unit:coverage`.

## Output

Before and after per module; the tests added and what each covers; the gaps deliberately left, with the reason. **Coverage floors are floors, not targets**
(kotlin.instructions.md): do not raise `koverVerificationFloor` in this change.
