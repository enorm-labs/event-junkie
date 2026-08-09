# Codebase Audit

Perform a comprehensive, whole-repository review of the code **and the architecture**. Step back from any single diff and assess the health of the project as a
whole: structure, size, duplication, maintainability, adherence to the conventions in `AGENTS.md`, and opportunities to simplify. This is a **read-only
investigation** — inspect, measure, and report; never mutate code or apply fixes unless the user explicitly asks in a separate step. Produce a prioritized,
skimmable report where every claim is backed by a concrete file, count, or command output.

## Scope & intent

This is **not** `code-review` (which is scoped to the current diff/PR). This audit looks at the entire codebase across all four projects — `events-core`,
`events-bff`, `events-importer`, and `events-frontend` — plus build config, CI, and docs. The goal is to surface **actionable, high-value** structural and
quality issues: files/classes that have grown too large, duplicated logic that should be deduped, code that has become hard to follow, drift from documented
conventions, and places where a library or a small refactor would meaningfully simplify things.

Bias toward **signal over volume**. A long list of nits is less useful than a short list of the changes that would most improve maintainability. Distinguish
genuinely worthwhile changes from stylistic preference, and don't invent problems where the code is already clean — if a module is in good shape, say so.

Before reporting, read the ground truth for this project:

- **`AGENTS.md`** — the architecture decisions, module boundaries, and Kotlin/Spring/Vue conventions the code is meant to follow. Findings should be measured
  against *these* conventions, not generic advice.
- **`docs/adr/`** — the ADRs record *why* things are the way they are (reactive stack, entity/domain separation, Modulith, scraping strategy, etc.). Do not flag
  a deliberate, documented decision as a defect; if you think an ADR itself is worth revisiting, say so explicitly and separately.
- **the importers' and scrapers' KDoc** in `events-importer/src/main/kotlin/de/norm/events/scraper/<venue>/` — each venue's accepted limitations and the
  reasoning behind its selectors; don't re-litigate them. Repairable defects are already filed as issues — check `build/BACKLOG.md`.
- **`.github/prompts/code-review.prompt.md`** — the per-diff checklist. Reuse its Backend/Frontend convention lists as the rubric for "best practices
  followed?", but apply them repo-wide instead of to a diff.

## How to gather evidence

Run the tooling the project already ships and let the numbers drive the findings. Prefer batching related commands.

**Size & shape**

```bash
# Largest Kotlin source files (excludes build output)
find . -name '*.kt' -not -path '*/build/*' -not -path '*/node_modules/*' | xargs wc -l | sort -rn | head -30
# Largest frontend source files
find events-frontend/src -name '*.vue' -o -name '*.ts' | xargs wc -l | sort -rn | head -20
```

Treat size as a *smell, not a verdict*: open the large files and judge whether they hold one cohesive responsibility (fine) or several that should be split
(e.g. a scraper that also does normalization, a service doing HTTP + parsing + persistence, a Vue view that mixes fetching, formatting, and layout). Flag
classes/files that mix concerns, have very long functions, deep nesting, or a large public surface. The recent split of `EventMappingExtensions` into three
cohesive files (see git log) is the model to hold others against.

**Static analysis & coverage** — run and read the output; don't just assert.

```bash
./gradlew detekt         # complexity, long methods, naming — read the reports, not just pass/fail
./gradlew koverLog       # per-module coverage; note modules/classes with weak coverage
./gradlew dependencyUpdates   # outdated libraries (also cross-check Dependabot groups)
```

For the frontend, `cd events-frontend && npm run lint` and `npm run type-check`. Note that `schema.d.ts` is generated — never count it as a hand-written
oversized file.

**Duplication** — the highest-value thing to hunt for in this codebase. The scraper package is where duplication tends to accumulate: many venue importers
implement the overview→detail pattern. Check whether shared logic actually lives in the shared extension files (`ScrapingExtensions.kt`,
`DateParsingExtensions.kt`, `EventTypeMapping.kt`,
`ArtistNameMapping.kt`, `EventFieldMapping.kt`) and `AbstractTwoPageWebsiteImporter`, or whether individual
`*OverviewPageScraper.kt` / `*DetailPageScraper.kt` / `*ApiScraper.kt` files reimplement the same date parsing, price normalization, title cleanup, or
artist-splitting inline. Grep for repeated literals, near-identical helper functions, and copy-pasted blocks across venue sub-packages. Also check test code for
boilerplate that a fixture or base class (`BaseControllerTest`, `*RequestFixtures`) already solves.

## What to assess

Work through these dimensions. For each, gather evidence, then judge impact before writing anything down.

### 1. File & class size / cohesion

Oversized files that mix responsibilities; long or deeply nested functions; classes with too many dependencies or too large a public API. Recommend concrete
splits (which responsibilities move where), following the project's feature-module file layout
(`*Controller/Service/Repository/Entity/Request/Response/Module.kt`).

### 2. Duplication & missing abstractions

Repeated logic across scrapers, services, or components that belongs in a shared utility, base class, or composable. Point at the specific existing home for it
(an extension file, `AbstractTwoPageWebsiteImporter`, a `use*` composable)
or propose a new one. Distinguish incidental similarity from true duplication worth extracting.

### 3. Readability & maintainability

Is the code still easy to follow? Unclear names, comments that explain *what* instead of *why*, dead/commented-out code, leftover TODOs, inconsistent patterns
between sibling modules, magic numbers/strings that should be named constants or config. Note where a newcomer would struggle.

### 4. Convention adherence (per `AGENTS.md` + code-review rubric)

- **Kotlin**: `val` over `var`, no unjustified `!!`, expression bodies, immutable collection types in signatures, higher-order functions over loops, trailing
  commas, named args for booleans, data classes for DTOs, sealed classes for restricted hierarchies.
- **Spring Boot**: constructor injection only (no field injection / `lateinit`), thin controllers, stateless services,
  `CoroutineCrudRepository` (no blocking calls in reactive paths), `@Query` SQL carrying the `events.` schema prefix,
  `@ConfigurationProperties` data classes, `*NotFoundException` + `GlobalExceptionHandler` error mapping.
- **Spring Modulith**: `@ApplicationModule(allowedDependencies = [...])` present and correct; run `ModularityTests` and report violations or suspiciously broad
  `allowedDependencies`.
- **Vue/TS**: `<script setup lang="ts">` only, no unjustified `any`, typed props/emits, computed for derived state, reusable logic in composables, multi-word
  component names, scoped styles, lazy-loaded routes.
- **API/DTO discipline**: controllers return `*Response` DTOs (never domain/entity), request DTOs carry Bean Validation annotations, `events-core` stays free of
  web/Swagger annotations.

### 5. Simplification via libraries or tooling

Places where a library the project already uses (or a small, well-justified new one) would remove hand-rolled code — e.g. repeated null/collection handling,
manual JSON walking that a mapping could express, date/price logic already covered by an extension. Be conservative: every proposed dependency must earn its
place (justify it, note the maintenance cost), and prefer using what's already on the classpath. Also flag tooling wins: detekt rules worth enabling, coverage
thresholds worth raising, config worth centralizing.

### 6. Architecture & module health

Module boundaries and dependency direction (does `events-core` stay web-free? do importer modules respect declared dependencies?); entity/domain/DTO separation
holding up; reactive-stack integrity (no blocking APIs leaking into request paths); consistency of the scraper pipeline across venues; whether the current
structure will scale as more venue importers are added. Cross-reference the relevant ADR before flagging anything architectural.

### 7. Tests & safety net

Coverage gaps on important classes (services, controllers, scrapers, normalizers) per `koverLog`; brittle or over-mocked tests; missing integration coverage for
cross-module flows; test files that are large because of boilerplate rather than cases. Note where the safety net is thin enough that refactoring would be
risky.

### 8. Build, dependencies & hygiene

Outdated or duplicated dependency versions (should be centralized per `AGENTS.md`); unused dependencies; OWASP/CVE posture; dead files, stale docs, or drift
between `AGENTS.md` and the actual code. If `AGENTS.md` describes something the code no longer does (or vice versa), flag the doc/code drift explicitly.

## Output

Write the report to `docs/audits/audit-<YYYY-MM-DD>.md` (create the `docs/audits/` directory if needed; use today's date). Do not ask whether to write the
file — always produce it. Structure it as:

1. **Summary** — a short health snapshot: module/file counts, the largest files, detekt/coverage headline numbers, and a one-line verdict per assessment
   dimension above (✅ healthy / ⚠️ N findings). Lead with the 3–5 highest-impact recommendations so the user can act without reading the whole document.
2. **Findings**, grouped by the dimensions above and ordered by severity within each:
    - 🔴 **high** — hurts correctness, maintainability, or architecture materially; worth doing soon.
    - 🟡 **medium** — clear improvement, not urgent.
    - 🟢 **low** — cleanup / nit / nice-to-have. Each finding: **what** it is, **where** (`path:line`), the **evidence** (command output, count, or code
      snippet), **why it matters**, and a **concrete recommendation** (the specific split, extraction, library, or config change — not "improve this"). Where
      useful, sketch the target structure.
3. **Quick wins vs. larger efforts** — separate the low-risk, high-value changes (do now) from the ones needing a design decision or a bigger refactor (discuss
   first). For anything architectural, name the ADR to update or add.

Keep it skimmable and evidence-backed — never a bare assertion or a raw number without a sample. Do not apply fixes, edit source, or change config as part of
the audit; reporting is the deliverable. If the user wants any finding fixed, that's a separate, explicitly-requested follow-up.
