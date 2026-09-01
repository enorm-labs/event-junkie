import com.github.jk1.license.filter.DependencyFilter
import com.github.jk1.license.filter.LicenseBundleNormalizer
import com.github.jk1.license.render.InventoryHtmlReportRenderer
import com.github.jk1.license.render.JsonReportRenderer
import com.github.jk1.license.render.ReportRenderer
import dev.detekt.gradle.Detekt
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.springframework.boot.gradle.dsl.SpringBootExtension
import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

// Centralized dependency versions live in `gradle.properties` – change them there to update
// all subprojects at once.

// Plugins are applied in the subprojects, so that they are only applied to the relevant modules
plugins {
    kotlin("jvm") apply false
    kotlin("plugin.spring") apply false
    id("org.springframework.boot") apply false
    id("io.spring.dependency-management") apply false
    id("org.jetbrains.kotlinx.kover")
    id("org.jlleitschuh.gradle.ktlint") apply false
    id("dev.detekt") apply false
    id("io.github.ben-manes.versions")
    id("org.owasp.dependencycheck")
    id("com.github.jk1.dependency-license-report")
}

// The commit the artifacts are built from, stamped into build-info.properties below.
//
// The FULL sha is stamped and the short form derived when rendering, so the footer's commit link
// and `git log` searches both work from one value. `providers.exec` rather than the
// `gradle-git-properties` plugin: this build runs with the configuration cache enabled, and a
// plugin that shells out to git at configuration time is exactly the pattern that breaks it —
// the same tax already paid for `dependencyCheckAggregate`.
//
// GITHUB_SHA wins when present so CI does not depend on the checkout's git metadata (actions/checkout
// makes a shallow clone; `rev-parse HEAD` still works there, but the env var is cheaper and exact).
// "unknown" keeps a build from a source tarball with no .git directory from failing outright.
val gitCommit: Provider<String> =
    providers
        .environmentVariable("GITHUB_SHA")
        .orElse(
            providers
                .exec { commandLine("git", "rev-parse", "HEAD") }
                .standardOutput
                .asText
                .map { it.trim() }
        ).orElse("unknown")

/**
 * Per-module line-coverage floors for `koverVerify`, with the number each module actually sits at
 * when the floor was set. Kept in one place so the whole policy is readable at a glance rather than
 * scattered across three build files.
 *
 *   events-core       100.0%  → floor 95   (pure domain; the data classes are excluded, so what is
 *                                           measured is the enum companions and MoneyExtensions)
 *   events-bff         98.6%  → floor 92
 *   events-importer    95.4%  → floor 90   (the largest module, and the one that grows fastest —
 *                                           one venue per PR — so it gets the most headroom)
 *
 * The aggregate is verified separately at the bottom of this file.
 */
fun koverVerificationFloor(module: String): Int? =
    when (module) {
        "events-core" -> 95
        "events-bff" -> 92
        "events-importer" -> 90
        else -> null
    }

/**
 * Whether a Kotlin compiler warning fails the build — off locally, on in CI (`-PwarningsAsErrors`,
 * passed by `build-backend.yml`).
 *
 * The Kotlin warning set is empty, and the only thing that keeps it empty is somebody noticing a
 * new line scroll past in a terminal. This makes CI notice instead. It is deliberately **not** on
 * by default: the warnings that appear unbidden are the ones a Kotlin or Spring Boot upgrade
 * introduces, and turning a dependency bump into a red local build punishes the person doing the
 * bump at the moment they are least able to act on it. In CI the same failure is a PR check on a
 * branch, which is where it belongs.
 *
 * Accepts a bare `-PwarningsAsErrors` (Gradle passes `""`) as well as an explicit `=true`/`=false`,
 * so `-PwarningsAsErrors=false` genuinely turns it off rather than enabling it by being present.
 *
 * **This does not cover the build scripts themselves.** `build.gradle.kts` is compiled by Gradle's
 * Kotlin DSL, not by these tasks, so a warning there — like the `arrayOf` intersection one below —
 * still only ever prints. Nothing available today changes that.
 */
val warningsAsErrors: Provider<Boolean> =
    providers
        .gradleProperty("warningsAsErrors")
        .map { it.isBlank() || it.toBooleanStrict() }
        .orElse(false)

subprojects {
    group = "de.norm"
    // `version` is deliberately NOT assigned here: it comes from `version` in gradle.properties,
    // which Gradle applies to every project. Re-adding an assignment would silently override that
    // single source of truth, and the build would stay green while the footer showed a stale
    // number. See docs/LEGAL.md §4.2.

    apply(plugin = "org.jlleitschuh.gradle.ktlint") // Version should be inherited from parent
    apply(plugin = "dev.detekt")
    apply(plugin = "org.jetbrains.kotlinx.kover")

    repositories {
        mavenCentral()
    }

    // see https://github.com/jlleitschuh/ktlint-gradle?tab=readme-ov-file#configuration
    configure<KtlintExtension> {
        // The actual ktlint version, see https://github.com/pinterest/ktlint/releases
        version = "1.8.0"
    }

    // This repository's own detekt rules (see :detekt-rules), on every module's analysis classpath —
    // including the rules module itself, which lints its own source with them. Self-reference is
    // deliberate and not a cycle: `detekt` consumes the jar, and nothing in building the jar
    // consumes `detekt`. Leaving it out is what would break, since detekt validates `detekt.yml`
    // against the rules it can see and rejects the `event-junkie` section as misspelled.
    dependencies {
        add("detektPlugins", project(":detekt-rules"))
    }

    // Detekt – static analysis for Kotlin. Customizations are defined in the root
    // detekt.yml config file. See https://detekt.dev/docs/introduction
    configure<dev.detekt.gradle.extensions.DetektExtension> {
        buildUponDefaultConfig = true
        config.setFrom(rootProject.file("detekt.yml"))
    }
    tasks.withType<Detekt>().configureEach {
        jvmTarget = "25"
        reports {
            html.required.set(true)
            checkstyle.required.set(false)
            // SARIF reports are uploaded to GitHub Code Scanning for inline PR annotations
            sarif.required.set(true)
            // Markdown reports are used by CI to post detekt metrics to the job summary
            markdown.required.set(true)
        }
    }

    // Kover – the exclusions every module shares. Filters do NOT propagate between projects (nor
    // into the aggregated report below), so anything that should be invisible everywhere has to be
    // configured per module — which is what this block does, instead of the same list copy-pasted
    // into three build files. Applies to `:events-<x>:koverLog` / `koverHtmlReport`, the per-module
    // numbers CI prints alongside the aggregate.
    //
    // Both patterns match classes carrying no executable logic, whose synthetic members would
    // otherwise be counted as uncovered:
    //   *Module   — Spring Modulith `@ApplicationModule` package markers (EventsCoreModule,
    //               MetaModule, VenueModule, …). `*` spans package segments, so one pattern covers
    //               every module package.
    //   *Fixtures — published `java-test-fixtures` factories: test support, not production code.
    //
    // Module-specific exclusions stay in the module (see events-core, which additionally drops its
    // plain domain data classes by exact name so the BFF/importer `*Entity` classes stay measured).
    configure<kotlinx.kover.gradle.plugin.dsl.KoverProjectExtension> {
        reports {
            filters {
                excludes {
                    classes(
                        "de.norm.events.*Module",
                        "de.norm.events.*Fixtures"
                    )
                }
            }

            // Line-coverage floors, enforced by `koverVerify`, which `check` (and therefore
            // `build`) already runs. Until now the task existed with no rules and passed
            // vacuously.
            //
            // **These are floors, not targets.** They sit several points below where each module
            // actually is, because a floor pinned to today's number fails the build for adding a
            // single uncovered line — which teaches people to lower it, and a threshold that gets
            // lowered on contact is worse than none. What these catch is a *material* regression:
            // a feature landing with no tests at all, or a test class quietly stopping running.
            //
            // Raising one is a deliberate act. Do it when a module has held comfortably above the
            // next step for a while — not in the same PR that happens to push the number up.
            koverVerificationFloor(name)?.let { floor ->
                verify {
                    rule("Line coverage of $name must not fall below $floor%") {
                        minBound(floor)
                    }
                }
            }
        }
    }

    // Stamp META-INF/build-info.properties into every Boot application, which auto-configures a
    // `BuildProperties` bean. The BFF serves it at `GET /meta` (the frontend footer) and both apps
    // expose it at `/actuator/info` (operators) — reading one bean, so the two can never disagree.
    // Configured here rather than per-module so the git plumbing below exists once.
    // See docs/LEGAL.md §4.3.
    plugins.withId("org.springframework.boot") {
        configure<SpringBootExtension> {
            buildInfo {
                // `build.time` is deliberately left at Boot's default. Two things worth knowing
                // before "optimising" it away: it is NOT a task input, so it does not make
                // `bootBuildInfo` re-run (verified — the task stays UP-TO-DATE across builds); and
                // suppressing it needs `excludes.add("time")`, since on Boot 4 an unset
                // `properties { time = null }` falls back to `Instant.now()` rather than being
                // omitted (BuildInfoProperties.getTimeIfNotExcluded). Drop it only if this project
                // ever adopts a reproducible-build requirement — until then, knowing when a
                // running instance was built is worth more to an operator than byte-identical jars.
                properties {
                    additional.put("commit", gitCommit)
                }
            }
        }
    }

    // Explode the fat jar into the layered layout the Dockerfiles consume (#426). Registered for
    // every Boot application, next to `buildInfo` above, for the same reason: one definition rather
    // than one per module.
    //
    // Spring Boot's reference Dockerfile does this extraction *inside* a builder stage
    // (`RUN java -Djarmode=tools …`). Doing it in Gradle instead is what makes the image build
    // contain no `RUN` at all — and therefore no target-architecture execution — so one runner can
    // emit a linux/amd64 + linux/arm64 manifest list with no QEMU and no build matrix. A JVM jar is
    // architecture-independent; only the base image differs per platform.
    //
    // `--application-filename application.jar` pins the extracted jar's name, which otherwise
    // defaults to the uber jar's — `events-bff-0.1.0-SNAPSHOT.jar`. Without it the Dockerfile's
    // ENTRYPOINT would have to track the project version.
    plugins.withId("org.springframework.boot") {
        val layersDir = layout.buildDirectory.dir("docker")
        val bootJarFile = tasks.named<BootJar>("bootJar").flatMap { it.archiveFile }
        // Resolved out here, against the *project*. Inside the `register` block below the implicit
        // receiver is the task, so a bare `the<JavaToolchainService>()` there looks the extension
        // up on the task and fails with "Extension of type 'JavaToolchainService' does not exist
        // … [ExtraPropertiesExtension]" — which reads like a plugin-ordering problem and is not one.
        val launcher = the<JavaToolchainService>().launcherFor(the<JavaPluginExtension>().toolchain)

        tasks.register<JavaExec>("bootJarLayers") {
            group = "distribution"
            description = "Extracts the Boot jar into build/docker/ as the layers the Dockerfile copies."

            inputs.file(bootJarFile).withPropertyName("bootJar")
            outputs.dir(layersDir).withPropertyName("layers")

            javaLauncher = launcher
            classpath = files(bootJarFile)
            // `-Djarmode=tools` hands the jar's own launcher a tools sub-command instead of the
            // application, so this runs Boot's extractor rather than the app. `mainClass` is
            // unused in that mode, but Gradle requires one to be set.
            mainClass = "org.springframework.boot.loader.launch.JarLauncher"
            jvmArgs("-Djarmode=tools")
            argumentProviders.add(
                CommandLineArgumentProvider {
                    listOf(
                        "extract",
                        "--layers",
                        // Refuses a non-empty destination otherwise, so a rebuild would fail.
                        "--force",
                        "--application-filename", "application.jar",
                        "--destination", layersDir.get().asFile.absolutePath
                    )
                }
            )
        }
    }

    // Applied to every Kotlin compilation, `main` and `test` alike — a warning in a test is the
    // same signal, and two of the three this rule was introduced alongside were in test code.
    // The per-module `kotlin { compilerOptions { … } }` blocks keep owning `-Xjsr305=strict` and
    // friends; only the shared, environment-dependent switch lives here.
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions {
            allWarningsAsErrors = warningsAsErrors
        }
    }

    // Netty uses native libraries via System.loadLibrary() which requires explicit opt-in
    // on Java 22+. Without this flag, the JVM emits warnings and will block access in a
    // future release. See: https://openjdk.org/jeps/472
    tasks.withType<Test> {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // Measured over 21 runs of `:events-importer:test`, not guessed (#975). At 1g the suite ran
        // its second half against the ceiling: 21 `Evacuation Failure: Allocation` events, 1023M of
        // 1024M. 2g is not enough either — 5 of 11 runs still failed, and exactly the ones where G1
        // expanded into the full 2048M. At 3g, 10 of 10 runs were clean and the heap settled at
        // 1508-1786M, so it stops being sized by its own ceiling. `events-bff` peaks at 322M and is
        // unaffected either way, which is why one shared value is cheaper than two to keep in step.
        // `-Xmx` is a ceiling, not a reservation: a suite needing 600 MB still uses 600 MB here.
        maxHeapSize = "3g"
    }
    tasks.withType<JavaExec> {
        jvmArgs("--enable-native-access=ALL-UNNAMED")
    }
    // `bootRun` runs with the *module* as its working directory, and `compose.yaml` is at the repo
    // root, so Spring's Docker Compose support looked one directory too deep and the app died at
    // startup with "No Docker Compose file found in directory '.../events-importer/.'". Pointing it
    // at the root file is what makes the documented `./gradlew :events-importer:bootRun` start its
    // own database, which is what README.md and DEVELOPMENT.md have always claimed it does.
    //
    // A system property rather than `args(...)`, because Gradle's `--args=` *replaces* configured
    // arguments. `scripts/dev-env.sh` passes its own `--spring.docker.compose.file`, and a command
    // line argument outranks a system property, so the script keeps full control and anyone else
    // passing unrelated `--args` does not silently lose the database.
    tasks.withType<BootRun>().configureEach {
        systemProperty("spring.docker.compose.file", rootProject.file("compose.yaml").absolutePath)
    }
}

// Kover – aggregates test coverage from all subprojects into a single report.
// Run `./gradlew koverHtmlReport` to generate an HTML report at build/reports/kover/html/.
// Run `./gradlew koverLog` to print a coverage summary to the console.
// `:detekt-rules` is deliberately absent: it is build tooling that never runs in production, so
// counting it would move the headline number for code no user executes.
dependencies {
    subprojects.filter { it.path != ":detekt-rules" }.forEach { kover(project(it.path)) }
}

// Per-module report filters do not propagate into this aggregated report, so mirror the
// events-core exclusions here to keep the headline aggregate meaningful: pure domain data
// classes, Spring Modulith markers, and test-fixture factories carry no logic and would only
// dilute the number. Exact class names are used for the domain data classes so the importer/BFF
// `*Entity` persistence classes stay measured; the `*Module` / `*Fixtures` patterns intentionally
// drop those non-logic classes across every module.
kover {
    reports {
        filters {
            excludes {
                classes(
                    "de.norm.events.artist.Artist",
                    "de.norm.events.event.Event",
                    "de.norm.events.event.LineupEntry",
                    "de.norm.events.genretag.GenreTag",
                    "de.norm.events.promoter.Promoter",
                    "de.norm.events.venue.Venue",
                    "de.norm.events.*Module",
                    "de.norm.events.*Fixtures"
                )
            }
        }

        // The aggregate floor — 95.6% today. Set at 90, below every per-module floor, because it
        // is a weighted average: the importer dominates it by size, so the aggregate cannot be
        // healthier than that module and a tighter number here would just duplicate its rule.
        //
        // This is the same figure CI's `mi-kas/kover-report` comment uses for `min-coverage-overall`,
        // except that one posts a comment and this one fails the build. Keep them in step, or
        // decide deliberately that the comment is the softer early warning.
        verify {
            rule("Aggregate line coverage must not fall below 90%") {
                minBound(90)
            }
        }
    }
}

// IntelliJ HTTP Client CLI – runs .http request files from the command line.
// Requires `ijhttp` to be installed (e.g. `brew install ijhttp` on macOS).
// Usage: `./gradlew httpTest` against a running importer (`scripts/dev-env.sh up importer`).
//
// The path is `importer/full-lifecycle.http`, not `full-lifecycle.http`. The `http/` directory was
// split into `importer/` and `bff/` and this task kept the flat name, so it failed on a file that
// does not exist — and because it needs a running importer, nothing in CI ran it to notice. Keep
// the subdirectory in the argument whenever a scenario moves.
//
// **Two files under `http/importer/` are deliberately absent, and both for the same reason.**
// `dev-seed.http` (86 import triggers) and `event-sources.http` (5) POST to `/import` and `/retry`,
// which make the importer scrape live venue websites. A task people run on demand must not put
// traffic on a venue's site — the same argument ADR-007 makes, and why `scripts/dev-env.sh` starts
// the importer with scheduling off. Run those two by hand when that is what you want.
//
// `events.http` is absent for a duller reason: it addresses a venue, artist and promoter it does
// not create, and every file now deletes its own fixtures, so nothing leaves rows for it to use.
// Giving it fixtures would duplicate `full-lifecycle.http`, which already covers that path.
tasks.register<Exec>("httpTest") {
    group = "verification"
    description = "Runs IntelliJ HTTP Client .http files against the local importer (requires ijhttp CLI and a running importer on port 8081)"
    workingDir = file("http")

    // Resolve the absolute path to ijhttp so that Gradle's Exec task can find it
    // even when /opt/homebrew/bin is not on the JVM's default PATH.
    val ijhttpPath =
        providers
            .exec {
                commandLine("bash", "-lc", "which ijhttp")
            }.standardOutput.asText
            .map { it.trim() }

    commandLine(
        ijhttpPath.get(),
        "--env-file",
        "http-client.env.json",
        "--env",
        "local",
        "-L",
        "VERBOSE",
        // Order is not significant, and the task is repeatable: every file now deletes the rows it
        // creates, so each runs alone, in any order, and any number of times against one database.
        // Before this they each left one fixture behind, and `full-lifecycle.http` then failed on a
        // duplicate slug — which is why the task could only ever run a single file.
        //
        // One exception, and it cannot be helped from here: an event carrying a genre auto-creates
        // a genre tag, and genre tags have no delete endpoint on purpose. So a `punk` tag survives.
        // It is inert — nothing asserts on the tag list's contents.
        "importer/health-and-openapi.http",
        "importer/venues.http",
        "importer/artists.http",
        "importer/promoters.http",
        "importer/genre-tags.http",
        "importer/data-quality.http",
        "importer/full-lifecycle.http"
    )
}

// OWASP Dependency-Check – scans all project dependencies for known CVEs using the
// National Vulnerability Database (NVD). Run `./gradlew dependencyCheckAggregate` to
// produce a single report covering all subprojects.
// Reports land in `build/reports/dependency-check/` (the plugin's default since 13.0.0;
// it was `build/reports/` before). Both CI workflows upload from that path behind a
// `hashFiles` guard that skips silently, so keep them in step with any change here.
// See https://jeremylong.github.io/DependencyCheck/dependency-check-gradle/
dependencyCheck {
    // `scanProjects` is deliberately NOT set: left empty, the plugin scans every project,
    // which is exactly what an aggregate report wants. Do not "restore" it as a list of
    // subproject *names* — the plugin matches `project.path` (`:events-core`), not
    // `project.name` (`events-core`), so a name list silently matches nothing and the scan
    // reports "Dependencies Scanned: 0" while the build stays green. See
    // AbstractAnalyze.shouldBeScanned: `scanProjects.isEmpty() || scanProjects.contains(project.path)`.
    // Build-tool classpaths, skipped because nothing on them is ever packaged or deployed: the
    // static-analysis and lint tools, and the Kotlin compiler plugin/script classpaths. They
    // pulled in their own (often much older) copies of Kotlin and logging libraries, which the
    // BOM overrides cannot reach and which produced findings against artifacts that only ever run
    // on a build agent — e.g. detekt's kotlin-reflect 1.6.10 and IntelliJ's repackaged coroutines.
    //
    // TRADE-OFF: this genuinely narrows the scan. A real CVE in detekt or ktlint will no longer
    // be reported here. That is accepted because those tools run only in CI and never process
    // untrusted input, and because Dependabot still watches them through the submitted dependency
    // graph. Do not extend this list to anything that ships.
    //
    // Names must match exactly — the plugin does `skipConfigurations.contains(configuration.name)`,
    // with no globbing — so a renamed or newly added tool configuration silently starts being
    // scanned again rather than erroring.
    //
    // `:detekt-rules` is skipped whole rather than by configuration name: it compiles against
    // detekt's API, so its `compileOnly` classpath carries exactly the build-agent-only artifacts
    // the list above exists to keep out — and `compileOnly` cannot be skipped globally without
    // narrowing the scan for the modules that ship. Matched by `project.path`, like `scanProjects`.
    skipProjects = listOf(":detekt-rules")
    skipConfigurations =
        listOf(
            "detekt",
            "detektPlugins",
            "ktlint",
            "ktlintBaselineReporter",
            "ktlintReporter",
            "ktlintRuleset",
            "kotlinCompilerPluginClasspathMain",
            "kotlinCompilerPluginClasspathTest",
            "kotlinScriptDef",
            "testKotlinScriptDef"
        )
    // Output formats: HTML for local review, SARIF for GitHub Code Scanning integration
    formats = listOf("HTML", "SARIF")
    // Fail the build if a CVE with CVSS score >= 7 (HIGH) is found
    failBuildOnCVSS = 7.0f
    // Suppress false positives via a shared suppression file (create as needed)
    suppressionFile = "owasp-suppressions.xml"
    // NVD API key speeds up database updates (rate-limited without it).
    // Set via NVD_API_KEY env var locally or as a GitHub Actions secret in CI.
    nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""
    // Treat cached NVD data as valid for 24h before re-contacting the API. Combined
    // with caching the data directory in CI, this means most runs skip the NVD update
    // entirely instead of re-downloading on every build — the NVD API is frequently
    // rate-limited or returns 503s, and each contact is a chance to fail the scan.
    nvd.validForHours = 24
}

// Gradle License Report – collects the licence of every runtime dependency across all three
// modules and emits JSON that the frontend's /legal/notices page renders. Run
// `./gradlew generateLicenseReport`, then `npm run generate:notices` in events-frontend to merge
// it with the npm side. See docs/LEGAL.md §9.
//
// This is the "Stage 1" tool: it lists and checks, but does not curate or scan source. ORT is the
// Stage 2 upgrade if policy enforcement beyond an allow-list is ever wanted.
licenseReport {
    // Only what actually ships. `runtimeClasspath` excludes the compile-only, test and build-tool
    // dependencies that never reach a user — attributing detekt or Testcontainers on a public page
    // would be noise, and their licences carry no distribution obligation for us.
    configurations = arrayOf("runtimeClasspath")

    // `:detekt-rules` is excluded for the same reason compile-only dependencies are: it is a detekt
    // plugin, so nothing on its classpath ships, and attributing detekt's own licence on a public
    // notices page would be noise.
    projects = arrayOf(project) + subprojects.filter { it.path != ":detekt-rules" }.toTypedArray()

    // The explicit `<ReportRenderer>` / `<DependencyFilter>` type arguments are load-bearing, not
    // decoration. Both properties are Java arrays, so Kotlin infers the element type from the
    // arguments rather than the target: every renderer and filter the plugin ships is a Groovy
    // class, so `arrayOf(...)` alone infers the *intersection* `ReportRenderer & GroovyObject`
    // and warns that reifying an intersection silently falls back to a common supertype. Naming
    // the interface is the fix the compiler asks for, and it becomes an error in language
    // version 2.3 (KTLC-13).
    renderers =
        arrayOf<ReportRenderer>(
            JsonReportRenderer("licenses.json", false),
            InventoryHtmlReportRenderer("licenses.html")
        )

    // Normalises the many spellings of the same licence ("Apache 2", "The Apache Software
    // License, Version 2.0", …) into one bundle so the notices page groups correctly. Without it
    // Apache-2.0 alone appears under half a dozen names.
    filters = arrayOf<DependencyFilter>(LicenseBundleNormalizer())

    // Checked by `./gradlew checkLicense`. Deliberately an *allow*-list here, unlike the CI
    // deny-list in dependency-review.yml: this runs over the full resolved tree where we control
    // the data, so an unknown licence should stop and be looked at rather than pass silently.
    //
    // JVM-only, hence the filename: the frontend is not a Gradle subproject, so npm dependencies
    // are invisible to this task. They have their own allow-list in the same vocabulary problem's
    // other dialect (SPDX ids) — config/allowed-licenses-npm.json, enforced by
    // `npm run check:licenses`. The two files are one policy expressed twice; change them together.
    allowedLicensesFile = file("config/allowed-licenses-jvm.json")
}
