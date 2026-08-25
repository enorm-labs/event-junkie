---
applyTo: "**/*.kt,**/*.kts,gradle.properties,detekt.yml,.editorconfig"
paths:
    - "**/*.kt"
    - "**/*.kts"
    - "gradle.properties"
    - "detekt.yml"
    - ".editorconfig"
---

# Kotlin, Gradle and the Backend Toolchain

How code here is written, and where its versions and thresholds live. Comments have their own file, and so does Markdown.

- **Application version**: lives in `version` in the root `gradle.properties` — the single source of truth. Gradle applies it to every project, so
  `build.gradle.kts` must **not** assign `version` in its `subprojects` block; a leftover assignment silently wins while the build stays green.
  `events-frontend/package.json` mirrors it **by hand**, deliberately without the `-SNAPSHOT` suffix (npm SemVer has no such convention), so the two files are
  intentionally not byte-identical — both move in one commit. A release build overrides the version from the tag (`-Pversion=0.1.1`) rather than editing the
  file. The version the site displays always comes from `GET /meta`, which is stamped from the build — never from `package.json`. See
  [docs/LEGAL.md](../../docs/LEGAL.md) §4.
- **Package structure**: `de.norm.events.<module-name>` — organize by feature/domain, not layer.
- **Kotlin DSL** for all Gradle build scripts (`build.gradle.kts`).
- **Kotlin 2.4.10** with **Spring Boot 4.1.0**; plugin versions pinned in `settings.gradle.kts` `pluginManagement`.
- **ktlint 1.8.0** enforced project-wide via root `subprojects` block; do not override per-module.
- **detekt 2.0.0-alpha.6** (`dev.detekt` plugin, migrated from `io.gitlab.arturbosch.detekt`) applied project-wide, with this repository's own rules from
  `:detekt-rules` on the analysis classpath (see the `event-junkie` section of `detekt.yml`). **The plugin version in `settings.gradle.kts` is the only place a
  detekt version is written.** `:detekt-rules` compiles against `the<DetektExtension>().toolVersion` — the version the plugin resolves for analysis — so a
  custom rule cannot be built against a different API than the one it is loaded with. Check it with `./gradlew :detekt-rules:detektToolVersion`; bumping the
  plugin needs no second edit. The 2.0 line is still pre-release; the alpha
  is tracked deliberately because it is what supports current Kotlin (see the compatibility-table link in `settings.gradle.kts`). Builds upon default config
  with overrides in root `detekt.yml` (currently only `MaxLineLength: 160`). Run `./gradlew detekt` to analyze all modules.
- **Max line length**: 160 characters (enforced by both `.editorconfig` and `detekt.yml`).
- Centralized library versions in **`gradle.properties`** (`java.version`, `jsoup.version`, `kotest.version`,
  `kotlin-logging.version`, `mockk.version`, `mockwebserver.version`, `slugify.version`, `spring-modulith.version`,
  `springdoc.version`), read in the module build scripts via `property("…")`; plugin versions in `settings.gradle.kts`
  `pluginManagement`. They live in `gradle.properties` rather than root `extra[...]` because Gradle 10 removes the implicit lookup of parent-project properties
  that the `extra[...]` form depended on.
    - **`gradle.properties` also holds a second, different kind of entry** — the "Spring Boot BOM overrides (CVE remediation)" block (`netty.version`,
      `postgresql.version`, `log4j2.version`, `jackson-2-bom.version`, `jackson-bom.version`) plus `scram.version`. These are **not** ordinary project versions
      and must not be bumped on sight. Each overrides a version the Spring Boot BOM would otherwise manage, and exists only because the BOM's version carries a
      known CVE. `io.spring.dependency-management` resolves BOM properties from Gradle project properties, so naming the BOM's own property here is enough to
      reach every module that applies the Boot plugin.
    - **They are temporary by design: delete each one once a Spring Boot release ships an equal or newer version.** An override kept past its purpose pins the
      project _behind_ the BOM, so later Boot upgrades stop raising that dependency and the staleness is invisible. `/update-dependencies` checks this on every
      run.
    - Two dependencies are not BOM-managed at all and are pinned by `constraints` blocks instead: **`scram.version`** (a transitive of `r2dbc-postgresql`, which
      pins the vulnerable version in every release) in both Boot modules, and **`log4j2.version`** reused in `events-core`. That last one matters —
      `events-core` applies `io.spring.dependency-management` but **not** the Boot plugin, so no BOM override reaches it. Importing the Boot BOM there is not a
      fix: without the Boot plugin nothing aligns the BOM's `kotlin.version`, and `compileKotlin` fails with a null plugin classpath. **When adding a BOM
      override, check `events-core` separately — verifying only the two Boot modules will report success while this one keeps the vulnerable version.**
- Use `val` for injected dependencies; constructor injection only (no field injection).
- Application config files use **`.yaml`** extension (not `.yml`).
- Kotlin compiler flags: `-Xjsr305=strict` (all modules) and `-Xannotation-default-target=param-property` (BFF + importer) are set in `compilerOptions`.
- **A Kotlin warning fails the build in CI, not locally.** The warning set is empty and stays that way because `build-backend.yml` sets
  `ORG_GRADLE_PROJECT_warningsAsErrors=true` for its whole job, which the root `build.gradle.kts` turns into `allWarningsAsErrors` on every `KotlinCompile`
  task (`main` and `test` alike). Locally it is off by default, deliberately: the warnings that appear unbidden come from a Kotlin or Spring Boot upgrade, and a
  red local build punishes whoever runs the bump at the moment they can least act on it — in CI the same failure is a PR check.
    - **Reproduce a CI failure locally with `./gradlew build -PwarningsAsErrors`**, and turn it off again with `-PwarningsAsErrors=false` (an explicit `false`
      really disables it; the switch is not merely presence-based).
    - **It does not cover the build scripts.** `build.gradle.kts` is compiled by Gradle's Kotlin DSL, not by these tasks, so a warning there only ever prints —
      and Gradle caches the compiled script by content hash, so it prints exactly once and then never again until the file changes. If you are hunting one, add
      a throwaway comment to bust the cache.
- **Kover** (`org.jetbrains.kotlinx.kover`) is configured for code coverage reports. Run `./gradlew koverLog` for a console summary or
  `./gradlew koverHtmlReport` for detailed HTML reports.
    - **Exclusions live in three places, and filters never propagate between them.** A class hidden from one report is still counted in the others unless it is
      excluded there too — this is the single thing to know before editing them.

        | Where                                                                         | Scope                     | Holds                                                              |
        | ----------------------------------------------------------------------------- | ------------------------- | ------------------------------------------------------------------ |
        | root `build.gradle.kts`, `subprojects { configure<KoverProjectExtension> … }` | every module's own report | `de.norm.events.*Module`, `de.norm.events.*Fixtures`               |
        | root `build.gradle.kts`, top-level `kover { }`                                | the aggregated report     | the shared patterns **again**, plus the events-core domain classes |
        | `events-core/build.gradle.kts`, `kover { }`                                   | events-core's own report  | its plain domain data classes, by exact name                       |

    - **What gets excluded, and why**: classes with no executable logic, whose synthetic members Kover would otherwise count as uncovered — Spring Modulith
      `@ApplicationModule` markers (`*Module`), published `java-test-fixtures` factories (`*Fixtures`), and events-core's plain domain data classes. Everything
      that carries logic stays measured.
    - `*` **spans package segments** in a Kover class pattern, so `de.norm.events.*Module` matches `de.norm.events.meta.MetaModule`. That is why the domain data
      classes are listed by exact name instead: a `de.norm.events.*Entity`-style pattern would silently swallow the BFF/importer persistence classes, which
      _should_ be measured.
    - Adding a new `*Module` marker or `*Fixtures` factory therefore needs no config change. Anything else does — in all three places.
    - **`koverVerify` enforces a line-coverage floor per module**, and `check` (so `build`) runs it. Floors are set in `koverVerificationFloor(...)` in the root
      `build.gradle.kts`, next to the number each module actually sits at.

        | Module            | Actual | Floor |
        | ----------------- | -----: | ----: |
        | `events-core`     | 100.0% |    95 |
        | `events-bff`      |  98.6% |    92 |
        | `events-importer` |  95.4% |    90 |
        | aggregate         |  95.6% |    90 |

    - **They are floors, not targets, and the gap is deliberate.** A floor pinned to today's number fails the build for one uncovered line, which teaches people
      to lower it — and a threshold that gets lowered on contact is worse than no threshold. These catch a _material_ regression: a feature landing untested, or
      a test class quietly ceasing to run. **Do not raise a floor in the same PR that pushes the number up**; raise it when a module has held comfortably above
      the next step for a while.
    - **If `koverVerify` fails, write the test.** Lowering the floor is a decision to be argued for in the PR description, not a way to go green.
    - **`-x test` implies `-x koverVerify`.** Skipping tests leaves no execution data, so every module reports 0% and the rule fails for a reason that has
      nothing to do with coverage. `build-backend.yml` passes both flags in its build step and runs `koverVerify` in the coverage step instead, after `test`.
      Any other `build -x test` invocation needs the same treatment.
- **Kotlin idioms** (per [official coding conventions](https://kotlinlang.org/docs/coding-conventions.html)):
    - **Trailing commas** at declaration sites (constructor params, function params, enum entries, collection literals) — produces cleaner VCS diffs.
    - **Expression bodies** — prefer `fun foo() = expr` over `fun foo() { return expr }` for single-expression functions.
    - **Named arguments** — use when a function has multiple parameters of the same type or Boolean parameters whose meaning isn't obvious from context.
    - **Immutable collection interfaces** — declare parameters and return types as `List`, `Set`, `Map` (not `MutableList` etc.) when the collection is not
      mutated. Use `listOf()`, `setOf()`, `mapOf()` factory functions.
    - **Expression form of control flow** — prefer `if`/`when`/`try` as expressions returning a value over imperative `return` inside branches.
    - **Higher-order functions over loops** — prefer `filter`, `map`, `flatMap`, `associate` over imperative `for` loops where readability is equal or better.
    - **Default parameter values** — prefer over function overloads.
    - **Scope functions** — use `let`, `apply`, `also`, `run`, `with` appropriately; avoid deep nesting of scope functions.
