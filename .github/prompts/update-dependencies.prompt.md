# Update Dependencies

Update project dependencies to their latest stable versions, applying only to versions managed by this project — never override versions controlled by Spring
Boot or Spring Dependency Management BOMs.

## Important

Always run git commands with the pager disabled (`git --no-pager ...`) to prevent hanging on interactive output.

**This is the routine sweep, not the security one.** A bump driven by a CVE belongs to [`/security-triage`](security-triage.prompt.md), which starts from the
alert and stops when the alert is gone. This command starts from "what is out of date" and applies only to versions we manage. They overlap on the same files, so
running both at once produces two half-finished bumps of the same property — do one, ship it, then the other.

## Running unattended

[`agent-dependencies.yml`](../workflows/agent-dependencies.yml) invokes this prompt as `/update-dependencies --unattended` from a runner. **It runs Step 12 and
nothing else**, which is a much narrower job than the name suggests and is the only part of this sweep that is worth automating at all.

The reason is that Dependabot already does the rest, and better. It owns `gradle`, `npm`, `docker`, `github-actions` and `opentofu`, with grouping, a suppression
that carries its argument, and a held major on the JRE. An agent re-deriving those bumps produces a second, worse pull request against the same files and a merge
conflict with the first. **Step 12's pins are the blind spot** — a tool version pinned as a plain string belongs to no ecosystem, so `HELM_VERSION`,
`ZIZMOR_VERSION`, `ACTIONLINT_VERSION`, `SHELLCHECK_VERSION` and their siblings rot in silence while the checks that use them keep reporting success. That is
the failure worth a scheduled job: a green gate that has stopped meaning anything.

- **Step 12 only, minus its last two rows.** `walg_version` and `k3s_version` feed `bootstrap.env` and are force-new attributes — bumping either plans a node
  replacement, production included. They are reported, never edited, and Step 12 already says to move them deliberately with the rebuild runbook open.
- **Nothing from Step 13.** Cluster component versions rot in production rather than in CI, and the check for them is a k3d rehearsal the runner cannot run.
- **A pin that appears in two or three workflows moves in all of them or in none.** `HELM_VERSION`, `HELM_UNITTEST_VERSION` and `SHELLCHECK_VERSION` each live in
  more than one file, and a half-applied bump surfaces as two checks disagreeing about the same file. If every copy cannot be found, report the pin and leave it.
- **`HELM_VERSION` stays on 3.x** whatever `helm/helm` says is latest. That constraint is semantic, not version lag — helm-controller embeds the Helm 3 SDK — and
  an unattended run is exactly where it would get "fixed".
- **A bump that turns a check red is a finding, not a thing to work around.** A newer analyser finding more is the tool working. Report the new findings and
  leave the pin raised, or revert it and say so; never pin back to make the gate green.
- **`--dry-run`** on top of it opens no pull request and writes the report to the job summary. Use it first, and after any change to this section.

The proof is the repository's own gates: every pin here is consumed by a workflow that lints or validates something, so a wrong version fails the pull request
rather than merging quietly.

**Your final message is the report, and there is no second turn.** The run ends the moment you stop calling tools, so a closing line like _"I'll compile the
report once the checks finish"_ ends it with that sentence as the whole deliverable — and the job still reports success. There is nobody to hand off to and
nothing to wait for: no reviewer reads the transcript, no follow-up prompt arrives, and any work you plan but do not do in this turn is simply lost. Finish the
work, then write the Output section below as your last message. This has already happened once, on a `--all` sweep that ended waiting for classification agents
it had no tool to spawn.

**Every count in the report carries the command that produced it.** A bucket line reading `DELETE 0` with nothing behind it is an assertion, and an assertion is
exactly what cannot be checked after the fact. Show the command and its output — a `git grep -c`, a script's summary line, a test name — so a reviewer, or the
next run, can re-run it and get the same number. This is the rule [`/codebase-audit`](codebase-audit.prompt.md) already applies: every claim backed by a
concrete file, count or command output.

**A zero needs its evidence, and so does every other number.** "Nothing to do here" is the finding nobody checks and the one that ends the run early. But the
rule is not "prove the zeros" — a run that carried a command for each of its zeros and none for its one non-zero count reported three candidates where the tree
held fifty-seven, and the count with no command behind it was the only one that was wrong. **A number you did not produce with a command is a guess, whatever
its size**, and a guess in a section headed _"reported for a human"_ is the one a human acts on.

Two runs of this prompt minutes apart once disagreed about whether a pattern still existed at all. Both reports were confident, well formatted, and one of them
was wrong. The command output is the only part of a report that cannot be plausible and false at the same time.

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
"Pins that are not ordinary project versions" is `log4j-api.version` and `scram.version`, neither of which is BOM-managed. There may also be `constraints`
blocks in module build scripts pinning a transitive for the same reason (e.g. `com.ongres.scram`).

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

## Step 12: The CI tool versions nothing else watches

**These are the repository's blind spot.** Dependabot covers `gradle`, `npm`, `docker`, `github-actions` and `opentofu` — but a _tool version pinned as a plain
string_ belongs to none of those ecosystems. `github-actions` updates `uses: azure/setup-helm@v5`; it has nothing to say about the `version: v3.19.0` passed to
it. So these rot silently, and a scanner or validator that is a year behind still reports success, which is the failure mode worth caring about: a green check
that has stopped meaning anything.

| Pin                     | Where                                                                                                              | Check against                                                      |
| ----------------------- | ------------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------------------ |
| `HELM_VERSION`          | `.github/workflows/validate-chart.yml`, `release.yml` **and** `image-scan-scheduled.yml` — all three move together | `helm/helm` releases, **staying on 3.x**                           |
| `FLUX_VERSION`          | `.github/workflows/validate-chart.yml`                                                                             | `fluxcd/flux2` releases                                            |
| `FLUX_SCHEMA_VERSION`   | `.github/workflows/validate-chart.yml`                                                                             | `fluxcd/flux-schema` releases — or `flux plugin list` locally      |
| `TRIVY_VERSION`         | `.github/workflows/release.yml` **and** `image-scan-scheduled.yml` — both must move together                       | `aquasecurity/trivy` releases                                      |
| `gitleaks` `rev:`       | `.pre-commit-config.yaml`                                                                                          | `gitleaks/gitleaks` releases                                       |
| `ZIZMOR_VERSION`        | `.github/workflows/validate-workflows.yml`                                                                         | `zizmorcore/zizmor` releases — the image tag has **no** `v` prefix |
| `ACTIONLINT_VERSION`    | `.github/workflows/validate-workflows.yml`                                                                         | `rhysd/actionlint` releases                                        |
| `HELM_UNITTEST_VERSION` | `.github/workflows/validate-chart.yml` **and** `release.yml` — both must move together                             | `helm-unittest/helm-unittest` releases                             |
| `SHELLCHECK_VERSION`    | `validate-scripts.yml`, `validate-infra.yml` **and** `validate-chart.yml` — all three move together                | `koalaman/shellcheck` releases                                     |
| `walg_version`          | `infra/modules/environment/variables.tf` — **and both `walg_checksums`**                                           | `wal-g/wal-g` releases — read the rules below before bumping       |
| `k3s_version`           | `infra/modules/environment/variables.tf`                                                                           | `k3s-io/k3s` releases — same rebuild consequence as `wal-g`        |

```sh
for repo in helm/helm helm-unittest/helm-unittest fluxcd/flux2 fluxcd/flux-schema aquasecurity/trivy \
            gitleaks/gitleaks zizmorcore/zizmor rhysd/actionlint koalaman/shellcheck wal-g/wal-g k3s-io/k3s; do
  printf '%-28s %s\n' "$repo" "$(gh api "repos/$repo/releases/latest" --jq .tag_name)"
done
```

Two things to be careful of. **`HELM_VERSION` appears in two workflows** and they must not drift apart — the whole point of the pin is that the chart is gated
against the same client everywhere. And **it must stay on Helm 3**, whatever `helm/helm` says is latest: Flux's helm-controller embeds the Helm 3 SDK, so
raising it to 4.x would gate the chart against a client that cannot install it. That constraint is semantic, not a version-lag; do not "fix" it.

**`HELM_UNITTEST_VERSION` appears in the same two workflows as `HELM_VERSION`**, and for the same reason: `release.yml` runs on a fresh runner and installs the
plugin itself. #430 chose a plugin install over the `helmunittest/helm-unittest` image precisely so `HELM_VERSION` could stay exact — the image's newest Helm 3
tag lags, and the plugin version it carries has no `--values` flag. If a bump ever tempts you back to the image, that is the constraint to re-check first.

**`SHELLCHECK_VERSION` appears in three** — `validate-scripts.yml`, `validate-infra.yml` and `validate-chart.yml` — for the same reason and with a sharper failure mode. It is pinned at all because the runner image's preinstalled
ShellCheck is older than a current local install and disagrees with it — v0.9.0 flags `SC2015` on `A && B || true`, v0.11.0 correctly does not — so an unpinned
job fails on files the author's own copy had just passed. `cluster-assertions.sh`, `k3d-rehearsal.sh`, `version.sh` and `version-test.sh` are linted by two of the three jobs, so a drifted pin surfaces as two checks
disagreeing about the same file. Bump all three in one commit, and when a bump does turn a job red, read the findings on their merits before
assuming the pin is wrong: a newer analyser finding more is the tool working.

A Trivy bump can turn a green scan red by adding advisories rather than by anything changing in the image. That is the tool working — treat the new findings on
their merits, do not pin back.

**The last two rows are not like the others, and the difference is expensive.** `walg_version` and `k3s_version` feed `bootstrap.env`, which is templated into
`user_data` — **a force-new attribute.** Bumping either one plans a _node replacement_, production included, where bumping Trivy edits a workflow. So:

- **Do not sweep them up with the routine run.** Move them when there is a reason, and take the rebuild deliberately with
  [docs/ops/CLUSTER_BOOTSTRAP.md](../../docs/ops/CLUSTER_BOOTSTRAP.md) § _Rebuilding a node_ open.
- **`wal-g` also carries two checksums**, one per architecture. Both environments have been x86 since 2026-08-21 — ARM cannot be bought anywhere in `eu-central` — so only the `amd64` one is exercised today, and that is exactly why both must stay correct: the unused one is the one nobody notices is wrong. Both move with the version, and they come from the
  release's own `.sha256` files — never from the tarball's host, which would verify only that the download completed:

    ```sh
    V=v3.0.9
    for a in amd64 aarch64; do
      printf '%-8s %s\n' "$a" "$(curl -fsSL "https://github.com/wal-g/wal-g/releases/download/$V/wal-g-pg-24.04-$a.tar.gz.sha256" | cut -d' ' -f1)"
    done
    ```

    The `aarch64` asset maps to the `arm64` key; the node picks by `dpkg --print-architecture`. A version bumped without its checksums fails the boot at
    `sha256sum -c` — intended, and an unpleasant way to discover a half-done edit.

- **`wal-g` is on the recovery path, not the request path.** Nothing about a stale version shows up in monitoring, and the moment you find out is the moment you
  are already restoring — so the natural time to review it is the quarterly restore drill, not this sweep.
  [docs/ops/BACKUPS.md](../../docs/ops/BACKUPS.md) §8 has the whole argument.

## Step 13: The cluster component versions, which are a different kind of blind spot

Step 12's pins rot in CI. **These rot in production**, and Dependabot cannot see them either: it has no Helm ecosystem, so a `version:` inside a Flux
`HelmRelease` belongs to no scanner. Six components run the platform and nothing watches any of them.

| Component                      | Where                                                                                                        | Check against                                           |
| ------------------------------ | ------------------------------------------------------------------------------------------------------------ | ------------------------------------------------------- |
| `openobserve-standalone`       | `deploy/clusters/staging/openobserve.yaml`                                                                   | `charts.openobserve.ai` — `helm search repo --versions` |
| `openobserve-collector`        | `deploy/clusters/base/collector.yaml` — shared, so one edit moves both clusters                              | same repository                                         |
| `opentelemetry-operator`       | `deploy/clusters/base/collector.yaml` — the same file                                                        | `open-telemetry/opentelemetry-helm-charts` **tags**     |
| `cert-manager`                 | `deploy/clusters/staging/cert-manager.yaml` **and** `production/cert-manager.yaml` — both must move together | `cert-manager/cert-manager` releases                    |
| `cert-manager-webhook-hetzner` | `deploy/clusters/staging/cert-manager-webhook.yaml`                                                          | `charts.hetzner.cloud` — `helm search repo --versions`  |
| `signal-cli-rest-api`          | `deploy/clusters/staging/signal-bridge.yaml` — **image, digest-pinned**                                      | `bbernhard/signal-cli-rest-api` releases                |

```sh
helm repo add openobserve https://charts.openobserve.ai >/dev/null
helm repo add hetzner https://charts.hetzner.cloud >/dev/null
helm repo update >/dev/null
for c in openobserve/openobserve-standalone openobserve/openobserve-collector hetzner/cert-manager-webhook-hetzner; do
    printf '%-40s %s\n' "$c" "$(helm search repo "$c" --versions -o json | python3 -c 'import json,sys; print(json.load(sys.stdin)[0]["version"])')"
done
for repo in cert-manager/cert-manager bbernhard/signal-cli-rest-api; do
    printf '%-40s %s\n' "$repo" "$(gh api "repos/$repo/releases/latest" --jq .tag_name)"
done
# The operator chart is one of many in its repository, so `releases/latest` names whichever chart
# shipped last — usually the collector. Filter the tags by this chart's own prefix instead.
printf '%-40s %s\n' opentelemetry-operator \
    "$(gh api 'repos/open-telemetry/opentelemetry-helm-charts/tags?per_page=100' \
        --jq '[.[].name|select(startswith("opentelemetry-operator-"))][0]')"
```

**Four things make this list different from Step 12's, and each one is a reason not to sweep them casually.**

**`openobserve-standalone` is pinned to the version an ADR was measured against.** [ADR-015](../../docs/adr/ADR-015_OBSERVABILITY_STACK.md) criterion 2 is a
claim about the footprint of **0.92.2** specifically. Bumping it does not invalidate the decision, but it does invalidate the measurement — so a bump means
re-checking resident memory against the ~1.5 GB ceiling and saying so, not just watching the pod come up.

**The `ZO_*` defaults are load-bearing and change between versions.** `ZO_LOCAL_MODE` has already been misread once as choosing storage rather than topology
(it chooses standalone-vs-cluster; `ZO_LOCAL_MODE_STORAGE` chooses the backend). Read the chart's changelog for default changes before the diff, because a
default that moves under you produces a pod that starts and behaves differently.

**`cert-manager` appears in both clusters and they must not drift.** A version difference between environments means staging stops being a rehearsal for
production, which is the only reason staging exists.

**`signal-cli-rest-api` is digest-pinned, so the tag alone is not the version.** Move the tag _and_ the `@sha256:` together, or the digest silently wins and
you have made no change at all — a bump that appears in the diff and not in the cluster.

**None of these belong in a routine sweep either.** Unlike Step 12's CI pins, bumping one of these changes what is running in a cluster. Do them one at a time,
watch the reconcile, and read [docs/ops/OPENOBSERVE.md](../../docs/ops/OPENOBSERVE.md) § _Keeping it up to date_ first — it carries the same list with the
operational consequences attached.

**The real fix is Renovate**, which supports Flux `HelmRelease` and `OCIRepository` natively and would make this step unnecessary. Until that exists, this list
is the only thing standing between the platform and a component two years behind.

## Step 14: Ship it

**An edit that is not committed is an edit that did not happen.** This sweep rewrites `gradle.properties`, `settings.gradle.kts`, `package.json`, the README
badges and the pinned tool versions in `.github/workflows/`. A change left in the working tree is lost when the session ends — on a runner that is the end of
the job, and the run still reports success.

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
- **Cluster components (Step 13) checked but deliberately not bumped**, and why — these are excluded from the routine sweep on purpose, so an empty line here
  should read as "checked, all current" rather than "not looked at".
