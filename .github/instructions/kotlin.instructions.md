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

- **No file carries the application version** (ADR-032). `scripts/version.sh compute` reads it from the release tags and the commits since the last one, and
  every CI build passes it as `-Pversion=…`. `version` in `gradle.properties` is a `0.0.0-SNAPSHOT` placeholder, `package.json` holds `0.0.0`, and
  `build.gradle.kts` must not assign `version` in `subprojects` — a leftover assignment silently wins over the flag. The site displays `GET /meta`, stamped
  from the build.
- **Versions live in two files, and nowhere in prose.** Plugin versions (Kotlin, ktlint, detekt, Kover) in `settings.gradle.kts` `pluginManagement`; library
  versions in `gradle.properties`, read with `property("…")` — not root `extra[...]`, which Gradle 10 stops resolving from a parent project.
    - **`gradle.properties` also holds "Pins that are not ordinary project versions"**, none BOM-managed and none bumped on sight. `scram` is a transitive of
      `r2dbc-postgresql` raised by a `constraints` block in both Boot modules. `log4j-api` and `spring-framework-bom` exist because `events-core` applies
      `io.spring.dependency-management` **without** the Boot plugin, so no Boot BOM reaches it and Modulith's transitives choose; importing the Boot BOM there
      fails `compileKotlin` on a null plugin classpath, and `spring-framework-bom` is the one BOM that can be imported because it manages `spring-*` only.
    - **A CVE-remediation override is temporary by design.** Setting a BOM property name in `gradle.properties` overrides it for every Boot module, and an
      override kept past its purpose pins the project _behind_ the BOM invisibly. Delete it once a Boot release ships an equal or newer version;
      `/update-dependencies` checks on every run. (`netty.version` is the current one, for CVE-2026-89044.)
    - **`log4j-api.version` is deliberately not `log4j2.version`, and `spring-framework-bom.version` not `spring-framework.version`** — each is the BOM's own
      property, and a pin `events-core` needs would silently become an override every Boot module resolves. Check a new pin's name against the BOM properties;
      verifying only the Boot modules reports success either way.
- **detekt: `settings.gradle.kts` is the only place a version is written.** `:detekt-rules` compiles against `the<DetektExtension>().toolVersion`
  (`./gradlew :detekt-rules:detektToolVersion`), so a custom rule cannot be built against a different API than it is loaded with. The 2.0 pre-release line is
  tracked deliberately because it supports current Kotlin. Overrides in root `detekt.yml` (`MaxLineLength: 160`; `.editorconfig` agrees).
    - **`detekt`, `detektMain` and `detektTest` run different rules, and CI runs all three** (#407). `UnsafeCallOnNullableType`, `UseOrEmpty`,
      `UnusedPrivateProperty`, `LongParameterList` need resolved types, so `detekt` alone silently _skips_ them.
    - **Four rules are tuned for test sources in `detekt.yml`, each with its reason there** — a fixture builder's parameter list is the record it builds,
      every `IgnoredReturnValue` is a `coVerify` recording, a test naming `Dispatchers.IO` _is_ the substitution `InjectDispatcher` asks for, `lateinit val`
      does not exist. Read the file before a `@Suppress`.
- **ktlint is enforced from the root `subprojects` block**; do not override per module. Package structure `de.norm.events.<module>` by feature, not layer.
  Kotlin DSL for every build script; `.yaml`, not `.yml`, for application config; constructor injection into `val`s only; compiler flags `-Xjsr305=strict`
  (all) and `-Xannotation-default-target=param-property` (Boot modules) in `compilerOptions`.
- **A Kotlin warning fails the build in CI, not locally.** `build-backend.yml` sets `ORG_GRADLE_PROJECT_warningsAsErrors=true`, which the root build turns into
  `allWarningsAsErrors` on every `KotlinCompile`. Off locally on purpose — unbidden warnings arrive with a Kotlin or Boot bump, and a red local build punishes
  whoever runs it. **Reproduce with `./gradlew build -PwarningsAsErrors`**; `-PwarningsAsErrors=false` really disables it. It does not cover `build.gradle.kts`
  itself, and Gradle caches the compiled script by hash, so a script warning prints once and never again until the file changes.
- **Kover**: `koverLog` for a summary, `koverHtmlReport` for detail, and **`koverVerify` enforces a line-coverage floor per module** from
  `koverVerificationFloor(...)` in the root `build.gradle.kts`, which runs under `check`.
    - **Exclusions live in three places and never propagate**: the `subprojects { configure<KoverProjectExtension> … }` block (every module's own report), the
      top-level `kover { }` (the aggregate — the shared patterns again, plus events-core's domain classes), `events-core/build.gradle.kts` (its own report).
      Excluded: `*Module` markers, `*Fixtures` factories, events-core's plain data classes — nothing with logic. `*` spans package segments, so the data
      classes are listed by exact name; a `*Entity` pattern would swallow the persistence classes. A new `*Module` or `*Fixtures` needs no change; anything
      else needs all three.
    - **Floors are floors, not targets, and the gap is deliberate.** A floor pinned to today's number fails on one uncovered line and teaches people to lower
      it. Do not raise a floor in the PR that pushes the number up. **If `koverVerify` fails, write the test**; lowering is argued in the PR description.
    - **`-x test` implies `-x koverVerify`** — no execution data, every module 0%. `build-backend.yml` passes both and runs `koverVerify` after `test`.
- **Kotlin idioms** per the [official conventions](https://kotlinlang.org/docs/coding-conventions.html): trailing commas at declaration sites, expression
  bodies, named arguments for same-typed or Boolean parameters, read-only collection interfaces, `if`/`when`/`try` as expressions, `filter`/`map` over loops
  where readability holds, default values over overloads, scope functions without deep nesting.
