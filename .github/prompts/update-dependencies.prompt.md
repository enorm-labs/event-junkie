# Update Dependencies

Update the dependencies this project manages to their latest stable versions. Never override a version a Spring Boot or Spring Modulith BOM controls.

## Important

`git --no-pager` on every git command.

**This is the routine sweep, not the security one.** A CVE-driven bump belongs to [`/security-triage`](security-triage.prompt.md), which starts from the alert.
The two overlap on the same files, so do one, ship it, then the other.

**A person runs this, from a terminal; nothing schedules it.** Two bots own the mechanical half, and this prompt must not touch what they own — editing a
version they watch produces a second pull request against the same file (ADR-024):

| Owner           | Watches                                                                                                                                                        |
| --------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| **Dependabot**  | `gradle`, `npm`, `github-actions`, `opentofu`, `docker`, `docker-compose`                                                                                      |
| **Renovate**    | Flux and the charts it installs, images in plain Kubernetes manifests, the CI tool pins in `.github/workflows/`, `.pre-commit-config.yaml`, the Gradle wrapper |
| **This prompt** | the judgement the bots cannot make: which major to take, whether a BOM override is obsolete, the whole picture in one report                                   |

Read the CI tool pins without touching them: `grep -rn '_VERSION:' .github/workflows/ | grep -vE 'VERSION: \$\{\{'`. A stale one is a Dependency Dashboard
question.

**A zero still needs its evidence.** Two runs of this prompt minutes apart once disagreed about whether a pattern existed; both reports were confident and one
was wrong. Every count in the summary carries the command that produced it.

## Step 1: The report

```bash
./gradlew dependencyUpdates          # build/dependencyUpdates/report.txt — read all of it
```

## Step 2: What we manage

- **`gradle.properties` `*.version` properties** — `jsoup`, `kotest`, `kotlin-logging`, `mockk`, `slugify`, `spring-modulith` (BOM), `springdoc`,
  `swagger-ui`. **`settings.gradle.kts` `pluginManagement`** — Kotlin, Spring Boot, dependency-management, Kover, ktlint, `dev.detekt`, versions plugin,
  OWASP. The ktlint _tool_ version is `version = "…"` in the root `build.gradle.kts` `configure<KtlintExtension>` block.
- **Bumping `dev.detekt` needs no second edit**: `:detekt-rules` follows the plugin (`./gradlew :detekt-rules:detektToolVersion`). A 2.0 alpha can move its API,
  so a bump that fails that module is the rule needing an update, not a bad version.
- **Anything declared without a version string comes from a BOM and is not touched**: `spring-boot-starter-*`, `spring-*`, `kotlin-*`, `kotlinx-coroutines-*`,
  `reactor-kotlin-extensions`, `jackson-module-kotlin`, `flyway-*`, `postgresql` / `r2dbc-postgresql`, `testcontainers`, `junit`.
- **Existing CVE-remediation overrides are the exception**, and they are temporary by design: a property named exactly as the Boot BOM names it
  (`netty.version` today), plus `constraints` blocks in module scripts (`com.ongres.scram`). The "Pins that are not ordinary project versions" block —
  `log4j-api`, `scram`, `spring-framework-bom` — is not BOM-managed. Never bump these because a newer release exists; **do check on every run whether they
  are obsolete** (Step 5).

## Step 3: Stable only

Reject any version containing `alpha`, `beta`, `rc`, `cr`, `m1`–`m3`, `dev`, `snapshot`, `eap`, `-M`, `preview` (case-insensitive).

## Step 4: Compatibility, and the release notes

- Spring Boot ↔ Spring Modulith: the [compatibility matrix](https://github.com/spring-projects/spring-modulith#compatibility-matrix). Kotlin ↔ Spring Boot: the
  Boot release notes. **A major bump is flagged for the user with its migration guide, never applied silently.**
- **Read the release notes for every minor and major bump**, and write what applies to this repository under _What the release gives us_: a new API that
  replaces a workaround here, a deprecation that names code we have, a default that changed. [docs/LINKS.md § 10](../../docs/LINKS.md#10-stack-reference-documentation)
  pairs every framework with its release notes; `context7` answers how the version we have works.

## Step 5: Apply, and prune

Library versions → `gradle.properties`; plugin versions → `settings.gradle.kts`; ktlint → root `build.gradle.kts`.

**Whenever Spring Boot or Spring Modulith moves, every CVE-remediation override is re-checked against what the BOM now supplies.** Comment the property out and
re-resolve:

```bash
./gradlew -q :events-importer:dependencies --configuration runtimeClasspath | grep -E "<artifact>" | sed 's/^[| +\\-]*//' | sort -u
```

BOM version ≥ pin: delete the override — kept past its purpose it holds the project _behind_ the BOM invisibly. Otherwise keep it and name the CVE. These are
upper-bound removals, never bumps; raising an override belongs to `/security-triage`.

**`swagger-ui.version` is overtaken by springdoc, not Boot**: after a `springdoc.version` bump, `./gradlew -q :events-bff:dependencyInsight --dependency swagger-ui
--configuration runtimeClasspath`; resolved ≥ pin means delete the pin and both modules' `constraints` for it. `SwaggerUiWebjarTest` keeps asserting the
bundle's DOMPurify version either way.

## Step 6: Verify

`./gradlew clean build`. On a failure caused by the bump: fix an import or a minor API change; revert a complex break and flag it.

## Frontend (`events-frontend/`)

Dependabot watches `npm` weekly, grouped, with `versioning-strategy: increase` — **check the open PRs first**; a `^` or `~` in `package.json` means that setting
was lost, not that a convention changed. This sweep is for taking everything at once and deciding a major deliberately.

1. `npm outdated` — and read [events-frontend/AGENTS.md](../../events-frontend/AGENTS.md): a stale local Node hides versions whose `engines` it fails.
2. Edit `package.json` by hand to exact pins, stable only (`alpha`, `beta`, `rc`, `next`, `canary`, `dev`, `snapshot`, `preview` rejected). Majors flagged, not
   applied. `oxlint` and `eslint-plugin-oxlint` move together; `vue` and `vue-router` stay compatible.
3. `npm update --save --save-exact`.
4. `npm run build`, then `npm run lint` and `npm run test:unit`. Same failure rule as Step 6.

## Step 11: README badges and the Kotlin link

The badge row in [`README.md`](../../README.md) hardcodes versions and nothing fails when they rot. `Kotlin` and `Spring Boot` to the exact version from
`settings.gradle.kts`; `Java` and `Vue.js` major only. Edit the version segment of the shields.io URL alone (`%20` is the space in `Spring%20Boot`). Check
every badge — a Boot bump can drag Kotlin along. A Kotlin minor also moves the `whatsnew<major><minor>.html` row in `docs/LINKS.md` § 10, then
`scripts/dashboard-parity.sh` regenerates the page. The `--gradle-version <x>` example in `docs/DEVELOPMENT.md` should match `distributionUrl`; this prompt does
not bump the wrapper.

## Step 12: Ship it

**An edit that is not committed did not happen.** Run whichever verification the diff touched, then [`/open-pr`](open-pr.prompt.md) with the summary as the
body. **One pull request per sweep. Nothing under `.github/workflows/`. If nothing moved, ship nothing and say so** — an empty sweep is the normal outcome.

## Output Summary

| Dependency | Previous Version | New Version | Location |
| ---------- | ---------------- | ----------- | -------- |

Then: which README badges were refreshed; what was skipped for lacking a stable release; each major applied, with its breaking changes; **What the release
gives us** per minor or major ("Nothing for us" is valid; skip patches); what was already current; **CVE overrides removed** because the BOM caught up, and
**kept**, naming the CVE. **Nothing about cluster components, CI tool pins or anything Renovate and Dependabot own** — a list invites the duplicate PR.

## Appendix: reviewing a Renovate pull request against a cluster component

**Not a step; nothing here is swept.** Renovate opens the PR; this is what it cannot know, for the review:

- **`openobserve-standalone` is pinned to the version [ADR-015](../../docs/adr/ADR-015_OBSERVABILITY_STACK.md) was measured against** (0.92.2). A bump
  invalidates the measurement, not the decision: re-check resident memory against the ~1.5 GB ceiling and say so. **The `ZO_*` defaults change between
  versions** (`ZO_LOCAL_MODE` is topology, `ZO_LOCAL_MODE_STORAGE` is the backend); read the changelog for default changes.
- **`cert-manager` is pinned in both clusters and must not drift**; Renovate groups them. A split PR means the grouping broke — wait.
- **Four images are digest-pinned**: `signal-cli-rest-api`, `postgres-exporter`, and the two `helm test` hook images, `curlimages/curl` and `grafana/k6`
  (#1751). Tag and `@sha256:` move together, or the digest silently wins. All four carry the digest **inside the tag string**, which is what makes Renovate
  move both: its replace template for each is `…{{#if newDigest}}@{{newDigest}}{{/if}}`. So this is a glance at the diff, not an edit to make by hand — and a
  bump that arrives with the tag alone is the thing to stop.
- **Flux**: Renovate edits the strings in `gotk-components.yaml` and does not regenerate it (#1075). First check `flux install --export … | diff -`
  (CLUSTER_BOOTSTRAP.md §9b); empty means complete. Then the Pod Security Admission patch's _"Re-check after a Flux upgrade"_ — a controller that violates
  `enforce: restricted` will not schedule, and takes every deploy with it. `FLUX_VERSION` in `validate-chart.yml` arrives as a second PR; **merge them
  together** — staging on v2.9.4 while CI validated with 2.9.5 is how #384 was found.
- **None is routine.** One at a time, watch the reconcile, and read [docs/ops/OPENOBSERVE.md](../../docs/ops/OPENOBSERVE.md) § _Keeping it up to date_.
