# Update Dependencies

Update project dependencies to their latest stable versions, applying only to versions managed by this project — never override versions controlled by Spring
Boot or Spring Dependency Management BOMs.

## Important

Always run git commands with the pager disabled (`git --no-pager ...`) to prevent hanging on interactive output.

**This is the routine sweep, not the security one.** A bump driven by a CVE belongs to [`/security-triage`](security-triage.prompt.md), which starts from the
alert and stops when the alert is gone. This command starts from "what is out of date" and applies only to versions we manage. They overlap on the same files, so
running both at once produces two half-finished bumps of the same property — do one, ship it, then the other.

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

`gradle.properties` has a block headed **"Spring Boot BOM overrides (CVE remediation)"** holding properties such as `netty.version` and `postgresql.version`.
These deliberately _do_ override BOM-managed versions, because the BOM's own version carried a known CVE. There may also be `constraints` blocks in module build
scripts pinning a transitive for the same reason (e.g. `com.ongres.scram`).

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

## Step 12: The CI tool versions nothing else watches

**These are the repository's blind spot.** Dependabot covers `gradle`, `npm`, `docker`, `github-actions` and `opentofu` — but a _tool version pinned as a plain
string_ belongs to none of those ecosystems. `github-actions` updates `uses: azure/setup-helm@v5`; it has nothing to say about the `version: v3.19.0` passed to
it. So these rot silently, and a scanner or validator that is a year behind still reports success, which is the failure mode worth caring about: a green check
that has stopped meaning anything.

| Pin                   | Where                                                                                                    | Check against                                                      |
| --------------------- | -------------------------------------------------------------------------------------------------------- | ------------------------------------------------------------------ |
| `HELM_VERSION`        | `.github/workflows/validate-chart.yml` **and** `release.yml` — both must move together                   | `helm/helm` releases, **staying on 3.x**                           |
| `FLUX_VERSION`        | `.github/workflows/validate-chart.yml`                                                                   | `fluxcd/flux2` releases                                            |
| `FLUX_SCHEMA_VERSION` | `.github/workflows/validate-chart.yml`                                                                   | `fluxcd/flux-schema` releases — or `flux plugin list` locally      |
| `TRIVY_VERSION`       | `.github/workflows/release.yml`                                                                          | `aquasecurity/trivy` releases                                      |
| `gitleaks` `rev:`     | `.pre-commit-config.yaml`                                                                                | `gitleaks/gitleaks` releases                                       |
| `ZIZMOR_VERSION`      | `.github/workflows/validate-workflows.yml`                                                               | `zizmorcore/zizmor` releases — the image tag has **no** `v` prefix |
| `ACTIONLINT_VERSION`  | `.github/workflows/validate-workflows.yml`                                                               | `rhysd/actionlint` releases                                        |
| `SHELLCHECK_VERSION`  | `validate-scripts.yml`, `validate-chart.yml` **and** `validate-infra.yml` — all three must move together | `koalaman/shellcheck` releases                                     |

```sh
for repo in helm/helm fluxcd/flux2 fluxcd/flux-schema aquasecurity/trivy gitleaks/gitleaks \
            zizmorcore/zizmor rhysd/actionlint koalaman/shellcheck; do
  printf '%-28s %s\n' "$repo" "$(gh api "repos/$repo/releases/latest" --jq .tag_name)"
done
```

Two things to be careful of. **`HELM_VERSION` appears in two workflows** and they must not drift apart — the whole point of the pin is that the chart is gated
against the same client everywhere. And **it must stay on Helm 3**, whatever `helm/helm` says is latest: Flux's helm-controller embeds the Helm 3 SDK, so
raising it to 4.x would gate the chart against a client that cannot install it. That constraint is semantic, not a version-lag; do not "fix" it.

**`SHELLCHECK_VERSION` appears in three**, for the same reason and with a sharper failure mode. It is pinned at all because the runner image's preinstalled
ShellCheck is older than a current local install and disagrees with it — v0.9.0 flags `SC2015` on `A && B || true`, v0.11.0 correctly does not — so an unpinned
job fails on files the author's own copy had just passed. `k3d-rehearsal.sh` and `version.sh` are linted by two of the three jobs, so a drifted pin surfaces as
two checks disagreeing about the same file. Bump all three in one commit, and when a bump does turn a job red, read the findings on their merits before
assuming the pin is wrong: a newer analyser finding more is the tool working.

A Trivy bump can turn a green scan red by adding advisories rather than by anything changing in the image. That is the tool working — treat the new findings on
their merits, do not pin back.

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
