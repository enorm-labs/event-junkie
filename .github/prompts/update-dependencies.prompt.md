# Update Dependencies

Update project dependencies to their latest stable versions, applying only to versions managed by this project — never override versions controlled by Spring
Boot or Spring Dependency Management BOMs.

## Important

Always run git commands with the pager disabled (`git --no-pager ...`) to prevent hanging on interactive output.

**This is the routine sweep, not the security one.** A bump driven by a CVE belongs to [`/security-triage`](security-triage.prompt.md), which starts from the
alert and stops when the alert is gone. This command starts from "what is out of date" and applies only to versions we manage. They overlap on the same files, so
running both at once produces two half-finished bumps of the same property — do one, ship it, then the other.

## Who runs this, and what it is for

**A person, from a terminal. Nothing invokes it on a schedule.** It exists for the half of dependency
work that no updater does: reading a Gradle report, deciding which majors are safe to take,
cross-checking compatibility across a BOM, and running the build to find out. That is judgement, and
it belongs to whoever is holding the terminal.

**Two bots own the mechanical half, and this prompt must not touch what they own.** Editing a version
they watch produces a second pull request against the same file, and a merge conflict with the first.
ADR-024 has the boundary; the short form:

| Owner           | Watches                                                                                                                                                        |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Dependabot**  | `gradle`, `npm`, `github-actions`, `opentofu`, `docker`, `docker-compose`                                                                                      |
| **Renovate**    | Flux and the charts it installs, images in plain Kubernetes manifests, the CI tool pins in `.github/workflows/`, `.pre-commit-config.yaml`, the Gradle wrapper |
| **This prompt** | the judgement calls above, and anything neither bot can express                                                                                                |

**So a local run is for when you want to think about the dependency set**, not to keep it current —
the bots do that, event-driven, and they do it better because they never forget. Reach for this when
a major is waiting and somebody has to decide, when a BOM override needs re-checking against what
Boot has caught up with, or when you want the whole picture in one report rather than a queue of
pull requests.

**To look at the CI tool pins without touching them**, which Renovate owns:

```sh
grep -rn '_VERSION:' .github/workflows/ | grep -vE 'VERSION: \$\{\{'
```

Read-only on purpose. A pin that looks stale is a Renovate question — check the Dependency Dashboard
before editing anything by hand.

**A zero still needs its evidence.** "Nothing to do here" is the finding nobody checks and the one
that ends a run early. A number produced without a command behind it is a guess whatever its size,
and a guess in a section headed _"reported for a human"_ is the one a human acts on. Two runs of this
prompt minutes apart once disagreed about whether a pattern existed at all. Both reports were
confident and well formatted, and one was wrong. Command output is the only part of a report that
cannot be plausible and false at the same time.

## Step 1: Generate the Dependency Update Report

Run the Gradle Versions Plugin to detect available updates:

```bash
./gradlew dependencyUpdates
```

This produces a report at `build/dependencyUpdates/report.txt` listing all dependencies with available updates. Read and analyze the full report.

## Step 2: Identify Which Versions We Manage

This project has two categories of dependency versions:

### ✅ Managed by us (update these)

Versions explicitly pinned in **`settings.gradle.kts`** (plugin versions) and **`gradle.properties`** (library versions). Currently:

**`gradle.properties` — library versions:**

| Property                  | Dependency                             |
| ------------------------- | -------------------------------------- |
| `jsoup.version`           | `org.jsoup:jsoup`                      |
| `kotest.version`          | `io.kotest:kotest-assertions-core`     |
| `kotlin-logging.version`  | `io.github.oshai:kotlin-logging-jvm`   |
| `mockk.version`           | `io.mockk:mockk`                       |
| `slugify.version`         | `com.github.slugify:slugify`           |
| `spring-modulith.version` | `org.springframework.modulith:*` (BOM) |
| `springdoc.version`       | `org.springdoc:springdoc-openapi-*`    |

**`settings.gradle.kts` — plugin versions:**

| Plugin ID                                   | Dependency                          |
| ------------------------------------------- | ----------------------------------- |
| `kotlin("jvm")` / `kotlin("plugin.spring")` | Kotlin compiler & plugins           |
| `org.springframework.boot`                  | Spring Boot Gradle plugin           |
| `io.spring.dependency-management`           | Spring Dependency Management plugin |
| `org.jetbrains.kotlinx.kover`               | Kover code coverage plugin          |
| `org.jlleitschuh.gradle.ktlint`             | ktlint Gradle plugin                |
| `dev.detekt`                                | Detekt static analysis plugin       |
| `io.github.ben-manes.versions`              | Gradle Versions Plugin              |
| `org.owasp.dependencycheck`                 | OWASP Dependency-Check plugin       |

Also check whether the **ktlint version** (`version = "..."` inside the `configure<KtlintExtension>` block in root
`build.gradle.kts`) has a newer stable release.

**Bumping `dev.detekt` needs no second edit.** `:detekt-rules` compiles against `the<DetektExtension>().toolVersion`, so it follows the plugin. Confirm with
`./gradlew :detekt-rules:detektToolVersion` after the bump — and note that a 2.0 **alpha** can move its API between pre-releases, so a bump that fails to
compile that module is the rule needing an update, not a bad version.

### ❌ Managed by BOMs (do NOT update these)

Dependencies whose versions come from the **Spring Boot BOM** (`org.springframework.boot` plugin) or the **Spring Modulith BOM**. These include, but are not
limited to:

- `org.springframework.boot:spring-boot-starter-*`
- `org.springframework:spring-*`
- `org.jetbrains.kotlin:kotlin-*` (version aligned by Kotlin plugin)
- `org.jetbrains.kotlinx:kotlinx-coroutines-*`
- `io.projectreactor.kotlin:reactor-kotlin-extensions`
- `tools.jackson.module:jackson-module-kotlin`
- `org.flywaydb:flyway-*`
- `org.postgresql:postgresql` / `org.postgresql:r2dbc-postgresql`
- `org.testcontainers:*`
- `org.junit.*` / `junit-platform-*`

**Rule of thumb**: If a dependency is declared _without_ an explicit version string (no `${property("...")}` or hardcoded version), its version comes from a BOM
and must NOT be overridden.

#### ⚠️ Exception: existing CVE-remediation overrides

`gradle.properties` can carry properties that deliberately _do_ override BOM-managed versions, because the BOM's own version carried a known CVE. The block is
**empty of overrides today** — Boot 4.1.1 caught up with every one and they were deleted — but the shape returns the next time an advisory lands ahead of a Boot
release, so recognise it: a property named exactly as the Boot BOM names it (`netty.version`, `postgresql.version`, `jackson-bom.version`). What remains under
"Pins that are not ordinary project versions" is `log4j-api.version`, `scram.version` and `spring-framework-bom.version`, none of which is BOM-managed. There
may also be `constraints` blocks in module build scripts pinning a transitive for the same reason (e.g. `com.ongres.scram`).

Do not treat these as ordinary version properties, and do not bump them just because a newer release exists — but **do check on every run whether they have
become obsolete**, per the pruning step below. They are temporary by design.

## Step 3: Filter for Stable Versions Only

The `dependencyUpdates` report may include milestone, alpha, beta, and RC releases. **Only update to stable releases.**

Reject any version containing these indicators (case-insensitive):
`alpha`, `beta`, `rc`, `cr`, `m1`, `m2`, `m3`, `dev`, `snapshot`, `eap`, `-M`, `preview`.

## Step 4: Cross-check Compatibility

Before applying updates, verify compatibility:

- **Spring Boot ↔ Spring Modulith**: Check the
  [Spring Modulith compatibility matrix](https://github.com/spring-projects/spring-modulith#compatibility-matrix) to ensure the Spring Modulith version is
  compatible with the Spring Boot version.
- **Kotlin ↔ Spring Boot**: Verify the Kotlin version is supported by the Spring Boot version (check the Spring Boot release notes).
- **Major version bumps**: For any major version upgrade (e.g., 5.x → 6.x), check the migration guide and note any breaking changes. Flag these for the user
  instead of silently applying them.

## Step 5: Apply Updates

Edit the version strings in the appropriate files:

- **Library versions** → `gradle.properties` (`*.version` properties)
- **Plugin versions** → `settings.gradle.kts` (`pluginManagement { plugins { ... } }`)
- **ktlint version** → root `build.gradle.kts` (`configure<KtlintExtension> { version = "..." }`)

### Prune obsolete CVE-remediation overrides

Whenever this run bumps **Spring Boot** or **Spring Modulith**, the new BOM may already supply a version equal to or newer than one we are pinning. Any override
that has been overtaken must be **deleted**, not left in place: it no longer protects against anything, and it silently holds us _behind_ the BOM, so future
Spring Boot upgrades stop raising that dependency and the staleness is invisible. An override kept past its purpose is a slow-acting downgrade.

For each entry in the "Spring Boot BOM overrides (CVE remediation)" block in `gradle.properties`, and each `constraints` block in the module build scripts,
compare the pinned version against what the BOM now supplies on its own — comment the property out and re-resolve:

```bash
./gradlew -q :events-importer:dependencies --configuration runtimeClasspath | grep -E "<artifact>" | sed 's/^[| +\\-]*//' | sort -u
```

**Not every override is overtaken by the Boot BOM, and the one that is not is easy to miss.** `swagger-ui.version` in `gradle.properties` holds
`org.webjars:swagger-ui` ahead of the version **springdoc** pins, not ahead of the Boot BOM — so the trigger for re-checking it is a `springdoc.version` bump,
which is a routine bump in this very step and not a Boot upgrade at all. After raising springdoc, check what its starter now brings on its own:

```bash
./gradlew -q :events-bff:dependencyInsight --dependency swagger-ui --configuration runtimeClasspath
```

If the resolved version without the constraint is greater than or equal to the pin, delete the pin and both modules' `constraints` blocks for it. The tests in
`SwaggerUiWebjarTest` (one per module) keep asserting the shipped bundle's DOMPurify version either way, so removing the pin is safe to attempt — the build says
whether it was premature.

Delete the override when the BOM's version is greater than or equal to the pin. Keep it otherwise, and say which CVE still justifies it. Note that these are
_upper_-bound removals, not bumps: raising a pinned override to a newer version than the CVE fix requires is out of scope here — that belongs to
[`/security-report`](security-report.prompt.md) for the diagnosis and [`/security-triage`](security-triage.prompt.md) for the change.

## Step 6: Verify the Build

After applying updates, run:

```bash
./gradlew clean build
```

If the build fails:

1. Read the error output carefully.
2. Check if the failure is caused by the update (breaking API change, removed method, etc.).
3. Fix straightforward issues (import changes, minor API adaptations).
4. For complex breaking changes, revert that specific update and flag it for the user.

## Frontend Dependencies (`events-frontend/`)

The frontend is a standalone npm project — not part of the Gradle build. Update it separately.

> **Dependabot also watches this now** (`npm`, weekly, grouped — see `.github/dependabot.yml`), so many of these bumps will already be sitting in open PRs.
> Check before starting: duplicating one means a conflict for whichever lands second. This skill stays useful for the sweep Dependabot cannot do — taking
> everything at once, deciding a major deliberately, and verifying the whole frontend afterwards rather than per-package.
>
> Dependabot is configured with `versioning-strategy: increase` precisely so it keeps the exact pins described below. If you ever see a `^` or `~` appear in
> `package.json`, that setting has been lost, not a convention that changed.

### Step 7: Check for Outdated Frontend Dependencies

```bash
cd events-frontend
npm outdated
```

This shows a table of all dependencies with their current, wanted, and latest versions.

### Step 8: Update Versions in `package.json`

Manually update the version strings in `events-frontend/package.json` to the latest stable versions reported by
`npm outdated`. This project uses exact (pinned) versions — no `^` or `~` prefixes.

**Rules:**

- Only update to **stable releases** — skip versions containing `alpha`, `beta`, `rc`, `next`, `canary`, `dev`,
  `snapshot`, `preview`.
- **Major version bumps** (e.g., 3.x → 4.x): Check the migration guide and flag breaking changes for the user instead of silently applying them.
- Keep `oxlint` and `eslint-plugin-oxlint` versions in sync (they share the same release cadence).
- Keep `vue` and `vue-router` compatible with each other (check Vue ecosystem compatibility).

### Step 9: Install Updated Dependencies

After editing `package.json`, run:

```bash
npm update --save --save-exact
```

This installs the updated versions and updates `package-lock.json`.

### Step 10: Verify the Frontend Build

```bash
npm run build
```

If the build fails:

1. Read the error output carefully.
2. Check if the failure is caused by the update (breaking API change, removed type, etc.).
3. Fix straightforward issues (import changes, minor API adaptations).
4. For complex breaking changes, revert that specific update and flag it for the user.

Optionally run linting and unit tests:

```bash
npm run lint
npm run test:unit
```

## Step 11: Refresh the README Version Badges

The badge row at the top of [`README.md`](../../README.md) hardcodes versions that this run may have changed. Badges are the first thing a reader sees, and
nothing in the build fails when they go stale — so update them here, in the same commit as the bump that made them wrong.

| Badge         | Source of truth                                                         | Update when                                    |
| ------------- | ----------------------------------------------------------------------- | ---------------------------------------------- |
| `Kotlin`      | `kotlin("jvm") version "..."` in `settings.gradle.kts`                  | always, to the exact version                   |
| `Spring Boot` | `id("org.springframework.boot") version "..."` in `settings.gradle.kts` | always, to the exact version                   |
| `Java`        | `java.version` in `gradle.properties`                                   | major only — the badge carries no minor/patch  |
| `Vue.js`      | `"vue"` in `events-frontend/package.json`                               | major only — the badge reads `3`, not `3.5.41` |

Only the version segment of the shields.io URL changes; leave the colour, logo and link target alone. Note that `%20` encodes the space in `Spring%20Boot`, so
edit the number, not the surrounding path. Check every badge even if you think the bump was unrelated — a Spring Boot bump can drag Kotlin along via
compatibility, and a frontend-only run can still cross a Vue major.

[docs/DEVELOPMENT.md](../../docs/DEVELOPMENT.md)'s "Updating the Gradle wrapper" section also carries a version — the `--gradle-version <x>` example (it lived
in the README before the restructure, so look there if this section has moved again). That one is illustrative rather than a claim, but an example older than
the wrapper itself reads as neglect, so match it to `distributionUrl` in `gradle/wrapper/gradle-wrapper.properties` whenever you notice a gap. This prompt does
not bump the wrapper itself; that is a separate manual step.

## Step 12: Ship it

**An edit that is not committed is an edit that did not happen.** This sweep rewrites `gradle.properties`, `settings.gradle.kts`, `package.json` and the README
badges. **It does not touch `.github/workflows/`** — the tool pins there are Renovate's, and editing one here is how a duplicate pull request starts.

The verification is already above: Step 6 for the backend, Step 10 for the frontend. Run whichever the diff touched, then [`/open-pr`](open-pr.prompt.md) with
the table from Output Summary below as the body.

**One pull request per sweep, not per bump.** Grouping is what `dependabot.yml` does for the ecosystems it owns, for the reason its own comments give: separate
pull requests per dependency is how people learn to ignore them.

**If nothing moved, ship nothing and say so.** These pins are checked far more often than they change, so an empty sweep is the normal outcome rather than a
failure — and an empty pull request costs a review to learn that.

## Output Summary

After completing the update, provide a summary table:

| Dependency | Previous Version | New Version | Location                                   |
| ---------- | ---------------- | ----------- | ------------------------------------------ |
| ...        | ...              | ...         | `build.gradle.kts` / `settings.gradle.kts` |
| ...        | ...              | ...         | `events-frontend/package.json`             |

Also note:

- Which **README badges** were refreshed, and which were already correct.
- Any dependencies that were **skipped** because only pre-release versions were available.
- Any **major version bumps** that were applied, with a brief note on breaking changes (if any).
- Any dependencies already at their **latest stable version** (no update needed).
- Any **CVE-remediation overrides removed** because the BOM caught up, and any **kept**, naming the CVE that still justifies each one.
- **Nothing about cluster components, CI tool pins, or anything else Renovate and Dependabot own.** A report that lists them invites somebody to act on the
  list, and acting on it is what produces the duplicate pull request. If one of them looks wrong, that is a Dependency Dashboard question, not a finding here.

## Appendix: reviewing a Renovate pull request against a cluster component

**Not a step, and nothing here is swept.** `.github/renovate.json5` watches every component below and
opens a pull request the day one is released (#384, ADR-024) — a first-class `flux` manager for
`HelmRelease` chart versions and a `kubernetes` manager for images in plain manifests. **Checking them
by hand means two mechanisms proposing the same bump**, which is
the duplication the whole boundary exists to prevent.

What survives is the part Renovate cannot know: **why some of these are not routine.** When a
Renovate pull request touches one of them, this is the review.

**`openobserve-standalone` is pinned to the version an ADR was measured against.**
[ADR-015](../../docs/adr/ADR-015_OBSERVABILITY_STACK.md) criterion 2 is a claim about the footprint
of **0.92.2** specifically. A bump does not invalidate the decision, but it does invalidate the
measurement — so approving one means re-checking resident memory against the ~1.5 GB ceiling and
saying so, not just watching the pod come up.

**The `ZO_*` defaults are load-bearing and change between versions.** `ZO_LOCAL_MODE` has already
been misread once as choosing storage rather than topology (it chooses standalone-vs-cluster;
`ZO_LOCAL_MODE_STORAGE` chooses the backend). Read the chart's changelog for default changes before
approving, because a default that moves under you produces a pod that starts and behaves differently.

**`cert-manager` is pinned in both clusters and they must not drift.** Renovate groups them into one
pull request for exactly this reason — if you ever see them split, the grouping has broken and the
merge should wait. A version difference between environments means staging stops being a rehearsal
for production, which is the only reason staging exists.

**`signal-cli-rest-api` and `postgres-exporter` are digest-pinned, so the tag alone is not the
version.** The tag _and_ the `@sha256:` must move together, or the digest silently wins and the diff
records a change the cluster never saw.

**Flux itself is in scope too, and it is the most careful review of the set.** Renovate edits the
version strings in `gotk-components.yaml` and does **not** regenerate it (#1075), so the first check
is `flux install --export … | diff -` against the file — empty output means the bump is complete, and
anything else is what a string edit could not reach. `docs/ops/CLUSTER_BOOTSTRAP.md` §9b has the full
command. Beyond that it is safe only because every local customisation is a kustomize patch in
`flux-system/kustomization.yaml` rather than an edit to the generated file — but
that file's Pod Security Admission patch says _"Re-check after a Flux upgrade"_, and it means it: the
`enforce: restricted` label is justified against the controllers in the **previous** manifest, and a
controller that violates it will not schedule, which takes every deploy with it. The pull request
carries this as a note; do not merge it unread.

**None of these is a routine bump.** Each changes what is running in a cluster. One at a time, watch
the reconcile, and read [docs/ops/OPENOBSERVE.md](../../docs/ops/OPENOBSERVE.md) § _Keeping it up to
date_ for the operational consequences.

**`FLUX_VERSION` and Flux itself are now one bot's problem, which is the point.** The pin in
`validate-chart.yml` names the CLI that validates these manifests; `gotk-components.yaml` names the
controllers that reconcile them. Those two drifting apart is how #384 was found — staging ran v2.9.4
while CI validated with 2.9.5, and nothing reported it. Renovate watches both now, so a release
produces two pull requests rather than one and a blind spot. **Merge them together.**
