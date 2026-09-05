# AGENTS.md

The conventions every change in this repository is held to. Written for AI agents, but it is simply this project's conventions written down.

## The short version

**Before you write anything:** read the section below that covers what you are touching. This project has strong opinions; the ones that apply to everything
are here, and the rest are one link away.

```bash
./gradlew clean build                    # compile, test, ktlint, detekt, coverage — the backend gate
./gradlew ktlintFormat                   # auto-fix formatting before fixing anything by hand
scripts/format-markdown.sh               # any .md change; the commit hook runs it anyway
cd events-frontend && npm run type-check && npm run lint && npm run test:unit
```

Skip the Gradle build for Markdown-only or frontend-only changes. `/verify` runs the full pre-PR sequence, including the infra and chart gates.

| Always-loaded section                                                             | When to read it                                                       |
| --------------------------------------------------------------------------------- | --------------------------------------------------------------------- |
| [Agent Instructions](#agent-instructions)                                         | Always. Git, formatting, ADR numbering, what never to run             |
| [Privacy & GDPR](#privacy--gdpr--re-check-when-infrastructure-or-features-change) | Any change to infrastructure, third-party requests, or what is logged |
| [Project Overview](#project-overview)                                             | The module split, and which project owns what                         |
| [Build & Dev Commands](#build--dev-commands)                                      | Running anything locally                                              |
| [Automating GitHub with `gh`](#automating-github-with-gh)                         | Scripting issues, pull requests or the board                          |
| [The Backlog](#the-backlog--github-issues)                                        | Filing, claiming or closing an issue                                  |
| [Key Files](#key-files)                                                           | "Where does X live?"                                                  |

**The rest is path-scoped and loads itself.** The detail that only matters for one kind of file lives in [`.github/instructions/`](.github/instructions), one
file per topic, each declaring the paths it applies to. Claude Code reads them through [`.claude/rules/`](.claude/rules) and GitHub Copilot reads them
directly; both pull a file into context when you touch a file it matches, so nothing here has to be loaded on the chance it is relevant. An agent that reads
only this file should follow the links.

| Rule file                                                           | Loads when you touch                                                    | Covers                                                                          |
| ------------------------------------------------------------------- | ----------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| [architecture](.github/instructions/architecture.instructions.md)   | `events-core/`, `events-bff/`, `events-importer/`                       | The reactive stack, the schema, migrations, DTOs, metrics                       |
| [kotlin](.github/instructions/kotlin.instructions.md)               | `*.kt`, `*.kts`, `gradle.properties`, `detekt.yml`                      | Idioms, where versions live, ktlint · detekt · Kover                            |
| [comments](.github/instructions/comments.instructions.md)           | Every source language                                                   | Few, short, about _why_ — and what lint already enforces                        |
| [documentation](.github/instructions/documentation.instructions.md) | `docs/**/*.md`                                                          | Simplified Technical English: how the sentences are written                     |
| [markdown](.github/instructions/markdown.instructions.md)           | `*.md`                                                                  | oxfmt, its pinned scope, and why it runs twice                                  |
| [vue](.github/instructions/vue.instructions.md)                     | `events-frontend/` `*.vue`, `*.css`                                     | SFC structure, Tailwind v4 + shadcn-vue, accessibility                          |
| [testing](.github/instructions/testing.instructions.md)             | backend `src/test/`, frontend `e2e/`, `__tests__/`                      | JUnit + Testcontainers, and Vitest + Playwright                                 |
| [kubernetes](.github/instructions/kubernetes.instructions.md)       | `deploy/**/*.yaml`                                                      | Audited API versions, the YAML boolean trap, PSS `restricted`                   |
| [ci-cd](.github/instructions/ci-cd.instructions.md)                 | `.github/workflows/`, `dependabot.yml`, `renovate.json5`, `release.yml` | Every workflow, the required checks, the Dependabot/Renovate boundary, fork PRs |

**Sibling files, none of them optional in their own subtree:** [`infra/AGENTS.md`](infra/AGENTS.md) opens with the OpenTofu commands that must never be run ·
[`deploy/AGENTS.md`](deploy/AGENTS.md) with the difference between rendering the chart and installing it · [`events-frontend/AGENTS.md`](events-frontend/AGENTS.md)
covers the SPA.

## Agent Instructions

- **Git non-interactive mode**: Always run git commands with the pager disabled to prevent the agent from hanging on interactive output. Use
  `git --no-pager <command>` or set the environment variable `GIT_PAGER=cat`. This applies to all git commands that may produce paged output (`log`, `diff`,
  `show`, `branch`, etc.). See [git docs](https://git-scm.com/docs/git#Documentation/git.txt---no-pager).
- **ktlint auto-format first**: When ktlint reports formatting issues, always run `./gradlew ktlintFormat` first to auto-correct them. Only edit files manually
  for issues that ktlint cannot auto-fix.
- **Reformatting is intentional — keep it**: Files in the working tree are routinely reformatted on purpose (IDE reformat-on-save, `./gradlew ktlintFormat`,
  `npm run format`). Treat that as deliberate and leave it in place. Never revert, re-fetch, re-download, or otherwise "restore" a file to an earlier shape
  because its indentation, tabs/spaces, line wrapping, attribute order, or trailing whitespace changed — and don't reformat _back_ to a previous style either.
  Review the content instead: `git --no-pager diff -w` (or `-b`) hides whitespace-only churn. Whitespace-only changes need no report, no explanation, and no
  action; they are not a signal that something went wrong.
    - This includes **test fixtures**, notably the scraper HTML snapshots under `events-importer/src/test/resources/scraper/`. A reformatted snapshot is still a
      valid fixture — Jsoup ignores indentation and attribute order — so a reformat is never on its own a reason to re-capture a page from the live site.
    - The one real caveat: in HTML, whitespace _between inline elements_ affects the text Jsoup returns (`<b>a</b><b>b</b>` yields `ab`, but with a newline
      between them it yields `a b`). So if a reformat makes a scraper test fail, that is a genuine finding — **raise it with the user**. Do not silently revert
      the file, and do not loosen the assertion to make it pass.
- **Build verification**: Always run `./gradlew clean build` after finishing an implementation to verify that all modules compile, tests pass, ktlint and detekt
  checks succeed, and Kover coverage thresholds are met. **Skip this step** when only Markdown documentation (`.md` files) or frontend files
  (`events-frontend/`) were changed — the Gradle build covers the backend modules only. A Markdown-only change is not check-free, though: run
  `scripts/format-markdown.sh` (see [.github/instructions/markdown.instructions.md](.github/instructions/markdown.instructions.md)), which the commit hook
  runs anyway.
- **Write a plan to `temp/`, which `.gitignore` covers.** Always a Markdown file, never the terminal alone and never a path outside the repository. The same
  goes for an audit, a draft release note, or anything else produced for a person to read rather than for the repository to keep. Use another location only
  when the user names one.
    - **Two reasons, and the second is the one that bites.** A plan committed to the tree becomes documentation nobody updates, and this repository already
      spends effort deleting those. A plan that exists only in the terminal is gone at the next compaction, and the reasoning behind it goes with it.
    - **Name it for what it is about**, so the next session finds one plan without reading all of `temp/`: `temp/<issue>-<slug>.md` for issue work,
      `temp/<topic>.md` otherwise. **No example here names a real file, and that is deliberate.** The rule below deletes a plan once its work lands, so any
      filename cited here becomes a dead reference within the week. Both of the ones that used to be here did.
    - **Format it: `scripts/format-markdown.sh temp/<file>.md`.** The formatter's default scope is the tracked tree, so a file under `temp/` is never reached
      by the commit hook, by CI, or by a bare `scripts/format-markdown.sh` — **it has to be named on the command line.** Skipping it costs nothing today and
      everything the moment a plan is pasted into an issue, a PR body or a document, which is where most of them end up: unformatted tables are the tell, and
      reformatting prose after the fact re-wraps every line it touches.
    - **Delete it when the work lands.** A finished plan is spent: its decisions belong in the code, the docs, or an issue. `temp/` is a workbench, not an
      archive.
- **No unsolicited git commits/pushes**: Never run `git commit`, `git push`, or `git rebase` (squash) unless explicitly asked to by the user.
- **Amend by default on a feature branch, and land one commit.** Once the branch carries a commit of yours, the next change **amends it** —
  `git commit --amend` — rather than adding a second. Push the result with `git push --force-with-lease`, never a bare `--force`. This matters more here than
  in most repositories: `main` allows only **Rebase and merge**, so every commit on the branch replays onto `main` exactly as written. Three "fix the lint"
  commits are three commits on `main` for good, and the branch's scratch history becomes the project's.
    - **A pull request normally lands as one commit.** When a branch does end up with several — a review round, a correction that could not be folded in —
      squash before merging. [`/squash-commit-message`](.github/prompts/squash-commit-message.prompt.md) writes the message for it.
    - **Amending changes what the commit contains, so rewrite the message with it.** A subject that describes the first version of a change is wrong once the
      change has grown, and it is the version that reaches `main`. The **PR title and description** are the same fact in two more places. Update all three
      together, or the pull request stops describing its own diff.
    - It is a default, not a prohibition. Keep commits separate when the user asks for that, or when a reviewer has already commented on one — rewriting a
      commit under review throws away the thread's anchor.
- **Documentation describes the current state. Replace, never append.** A document says what is true today, in the present tense. When something changes,
  **rewrite the affected passage** — do not add an "Update:" note, a dated banner, or a new section beside the old one. Two passages describing successive
  states of the same thing is a defect, not thoroughness: the reader cannot tell which one is live, and the older one is the one they will act on. This is the
  _Comments_ rule in [.github/instructions/comments.instructions.md](.github/instructions/comments.instructions.md) applied to Markdown, and for the same
  reason — prose that has to be maintained has to earn its keep.
    - **No intermediate states.** Delete completed phases, finished migrations and settled decisions. "Phase B started", "done for staging on 2026-08-19",
      "this table listed the first four until…" are facts about the past; git, the PR and the issue already hold them. **A plan whose phases have all shipped is
      deleted, not marked done.**
    - **Reasoning goes below the instructions**, in a final `## Background and history` section, or into an ADR, or nowhere. Never between the reader and the
      thing they came for. What survives a closed item is at most one sentence: an abandoned approach that is a live trap someone will re-introduce, named
      rather than retold. [docs/ops/PLATFORM_SETUP.md](docs/ops/PLATFORM_SETUP.md) is the worked example.
    - **Every document over ~150 lines opens with `## The short version`** — the commands, one short comment each, and the two or three rules that catch most
      changes. No prose. [docs/ops/CLUSTER_ACCESS.md](docs/ops/CLUSTER_ACCESS.md) and [docs/ops/DAILY_COMMANDS.md](docs/ops/DAILY_COMMANDS.md) are the models.
    - **A status banner is a liability with one exception.** It earns its place only while it warns of something _currently_ untrue or unfinished —
      [docs/LEGAL.md](docs/LEGAL.md)'s "not signed off" — and it is deleted the moment that stops being so. A banner describing progress ("applied, not yet
      proven") is the shape that goes stale silently, because nothing fails when it does.
    - **A blocker outlives the thing that blocked it.** When you close an item on a list of open questions, **delete the item**; do not annotate it as done.
      LEGAL.md §14 carried an item whose stated reason had been false for days, twice, and it is the specific way such a section rots.
    - **An issue or ADR reference is a pointer, not a summary**, exactly as in code. `see #540`, and stop.
- **Documentation under `docs/` is written in Simplified Technical English.** The rules on this page say what a document may contain. [ASD-STE100](https://www.asd-ste100.org/)
  says how the sentences are built: one idea each, 25 words at most, active voice, no semicolons, no phrasal verbs. The whole rule, the exemptions and the
  `asd-ste100` skill that applies it are in [.github/instructions/documentation.instructions.md](.github/instructions/documentation.instructions.md), which
  loads itself when you touch a file under `docs/`. The same discipline already governs code comments
  ([.github/instructions/comments.instructions.md](.github/instructions/comments.instructions.md) § How to write the sentences). **Keep every hedge at its
  original strength** — the one way an STE rewrite goes wrong is by shortening _may have failed_ into _failed_. See #733.
- **A red `release.yml` on `main` blocks every release, and the cause is usually not the change that landed.** The image scan gates on fixable findings
  in the base images, so an Alpine advisory turns `main` red with nothing in the diff to show for it. Look at the latest run before cutting; `cut-release.yml`
  refuses a red one. The levers, and the amd64 trap, are in [docs/ops/RELEASING.md § Publishing is blocked](docs/ops/RELEASING.md#publishing-is-blocked).
- **Correct the docs in the same change that makes them wrong.** A behaviour change that leaves a document describing the old behaviour is incomplete work, not
  a follow-up — and the document to fix is the one a reader would reach for, which is usually not the one you were editing.
- **ADR numbers are claimed by writing the ADR, never by planning one.** A document that says _"needs ADR-0NN"_ for an ADR nobody has written yet is a
  reservation the numbering scheme does not honour: the next ADR actually written takes that number, and the reference silently starts pointing at an unrelated
  decision. This has already happened twice to the same planned ADR. **Refer to a future ADR by its title only** — _"needs an ADR: AI-Assisted Data Quality"_ —
  and assign the next free number from `docs/adr/` at the moment you create the file.
- **GitHub CLI (`gh`)**: `gh` is a prerequisite, not an optional convenience — install it with `brew install gh` and authenticate with `gh auth login`; it is
  set up for GitHub.com and enterprise instances. Use it for GitHub interactions such as creating/viewing PRs, managing issues, checking CI status, and browsing
  repositories. **How to drive it is a skill**, vendored into [`.claude/skills/gh/`](.claude/skills/gh/SKILL.md) from
  [`cli/cli`](https://github.com/cli/cli/tree/trunk/skills/gh); read [its `VENDORED.md`](.claude/skills/gh/VENDORED.md) before editing anything in that
  directory, and [Automating GitHub with `gh`](#automating-github-with-gh) for what the skill does not know about this repository.
  See also [GitHub CLI quickstart](https://docs.github.com/en/github-cli/github-cli/quickstart) and
  [CLI reference](https://docs.github.com/en/github-cli/github-cli/github-cli-reference).

## Privacy & GDPR — re-check when infrastructure or features change

The public privacy notice (`/legal/privacy`) and the imprint describe **what this system actually does**. Each exists as **two documents** —
`PrivacyView.en.vue` and `PrivacyView.de.vue` under `events-frontend/src/views/legal/`, with the German one authoritative — so updating one and not the other
leaves the site stating two different things. They are only correct as long as that description matches reality, and the changes that break them do not look
like privacy work. **Before merging, check whether your change falls into any category below — and if it does, say so explicitly in the PR description and
update
[docs/LEGAL.md](docs/LEGAL.md) §7 plus the privacy page in the same PR.**

**Infrastructure and operations**

- Choosing or changing a hosting provider, CDN, WAF, DNS, mail, backup, or object-storage provider — each is a processor that must be _named_, needs an Art. 28
  DPA in place, and, if it is outside the EU/EEA, a transfer mechanism. [ADR-012](docs/adr/ADR-012_CLOUD_PLATFORM.md) leaves **one processor, Hetzner**, and the
  notice says so. `INFRASTRUCTURE_IS_PROPOSED` stays `true` until §5 of both notices has been checked against what actually runs — the platform existing is not
  the moment that changes, the check is (docs/LEGAL.md §14).
- Changing log content, log retention, or IP handling (truncation/anonymisation) — the notice states a retention period; it must be the real one.
- Adding monitoring, error tracking, uptime checks, APM, or a metrics backend that receives request or user data.
- Adding a staging or preview environment reachable from the internet. **Note the SEO hazard alongside the privacy one:** the build emits a `robots.txt` that
  allows all crawlers and a `sitemap.xml` naming the production origin, so any environment serving that build invites indexing. Override both per environment.

**Features**

- **Anything stored on the visitor's device** — a cookie, `localStorage`, `sessionStorage`, IndexedDB, or the Cache API. § 25 TDDDG covers _storage on terminal
  equipment_, not cookies specifically. Today every stored item is strictly necessary, so **no consent banner is required** — that is a property worth
  protecting deliberately. The first non-essential item (analytics ID, A/B bucket, recommendation history) makes a consent banner mandatory and is a product
  decision, not an implementation detail. **Escalate rather than implement.**
- **Any third-party resource loaded by the browser** — a font, script, iframe, map, embed, social widget, or image hotlinked from another host. Each one
  transmits the visitor's IP address to that host. Fonts are self-hosted (`@fontsource-variable/geist`) for exactly this reason; keep it that way.
- **Any outbound call made from the frontend** to a domain we do not operate. The GitHub API is the tempting one — see LEGAL.md §4.1 for why the footer's
  version does not come from it.
- **Accounts, login, sessions, newsletter, contact form, comments, favourites, or notifications** — each introduces user data we do not process at all today,
  and needs its own legal basis, retention period and deletion route.
- **New personal data in the domain model.** Artist names are already personal data (§7.3 of the plan, and §4 of the privacy notice). Adding contact details,
  social handles, photographs of identifiable people, or user-submitted content extends that materially.
- **Either of the two above also changes what the processor contract has to cover**, and that is the half nobody remembers. `LEGAL.md` §7.3a records the exact
  categories of personal data and of data subject declared in the Hetzner AVV — **a category not on that list is outside the agreement**, however carefully the
  privacy notice is updated. An email address or a phone number stored anywhere is the clearest example: it introduces a category the current contract was not
  written against. Update §7.3a and re-check the AVV in the same change, not afterwards.
- **Analytics of any kind**, including self-hosted and "cookieless" tools. Self-hosted and cookieless is a better posture, but it is still processing and still
  needs a legal basis and a notice entry.

**Commercial changes** — ads, affiliate links, sponsorships, donations, or paid features also change the § 5 DDG imprint analysis, not just the privacy notice.

When in doubt, flag it in the PR rather than deciding silently. The cost of raising it is a sentence; the cost of missing it is a legal defect on a public site.

## Project Overview

Event Junkie is a multi-module Kotlin/Spring Boot application for discovering music events in Berlin. It uses a **Gradle multi-project build** with three
application subprojects and one build-tooling subproject sharing a root `settings.gradle.kts`, plus a standalone frontend project:

- **`events-core`** – Shared domain model library (no Boot app); consumed via `project(":events-core")` dependency. Applies `java-library`, `maven-publish`, and
  `java-test-fixtures` plugins (add fixtures under `src/testFixtures/`). Uses `api()` scope for `spring-modulith-starter-core` so it's transitively available to
  consumers. Contains domain data classes organized by feature: `artist/`, `event/`, `promoter/`, `venue/`. Also defines enums (`EventType`,
  `EventStatus`, `ArtistRole`) and the `LineupEntry` value object in `event/Event.kt`.
- **`events-bff`** – Backend-for-Frontend REST API (Spring Boot 4 + WebFlux + R2DBC). Runs on default port `8080`.
- **`events-importer`** – Imports events from external sources into the database (Spring Boot 4 + WebFlux + R2DBC + Flyway). Runs on port `8081`. Owns all
  Flyway migrations under `src/main/resources/db/migration/`.
- **`detekt-rules`** – This repository's own detekt rules (currently `LongComment`), loaded onto every module's `detektPlugins` classpath by the root build and
  configured under the `event-junkie` key in `detekt.yml`. Compiles against the detekt version the plugin itself resolves, so there is no version to keep in
  step. Build tooling: nothing it contains ships, so it is left out of the Kover aggregate, the licence report
  and the OWASP scan — each exclusion sits next to its reason in the root `build.gradle.kts`.
- **`events-frontend`** – Vue 3 SPA (Vite 8, TypeScript 6, Vue Router). Uses oxlint/oxfmt for linting/formatting. Not a Gradle subproject — managed separately
  via npm. Requires Node `>=24.15.0` (see `engines` in `package.json`) — a **patch** floor, because jsdom 30's supported range excludes 24.0–24.14. The floor
  has moved twice and each move was forced by a dependency rather than chosen; `events-frontend/AGENTS.md` records both, and ADR-013 covers the earlier one.

## Build & Dev Commands

```bash
./gradlew clean build          # Full build (all modules, tests, ktlint)
./gradlew :events-bff:bootRun  # Run BFF (auto-starts Postgres via compose.yaml)
./gradlew :events-importer:bootRun  # Run importer
./gradlew ktlintCheck          # Lint all modules
./gradlew ktlintFormat         # Auto-fix formatting
./gradlew detekt               # Static analysis, syntax-tree rules only (all modules)
./gradlew detektMain           # Static analysis with type resolution over main — a different rule set
./gradlew detektTest           # The same over test sources; CI runs all three
./gradlew koverLog             # Print test coverage summary per module
./gradlew koverHtmlReport      # Generate HTML coverage reports
./gradlew dependencyUpdates    # Check for newer dependency versions
./gradlew dependencyCheckAggregate --no-configuration-cache  # OWASP Dependency-Check (CVE scan)
./gradlew httpTest                  # Run .http files via IntelliJ HTTP Client CLI (requires ijhttp + running importer)
```

Performance tests against the BFF's read API ([k6](https://k6.io); `brew install k6`, and the BFF has to be running):

```bash
k6 run perf/smoke.js           # every endpoint once — safe to run anywhere, ~1s, tolerates an empty DB
k6 run perf/load.js            # sustained realistic load — watch whether p95 climbs with the VU count
k6 run perf/spike.js           # a sudden surge — the finding is whether it recovers, not the peak
```

See [perf/README.md](perf/README.md) for what each answers, the thresholds and how to re-baseline them, and why there is deliberately no CI workflow yet.

Infrastructure ([`infra/`](infra), OpenTofu). **Read [infra/AGENTS.md](infra/AGENTS.md) before touching any of it** — it opens with the commands that must
never be run there. These are the safe ones, need no credentials, and are what `validate-infra.yml` runs:

```bash
tofu fmt -recursive -check -diff infra
export TF_DATA_DIR="$(mktemp -d)"               # a used checkout makes init reach the state bucket
tofu -chdir=infra/<stack> init -backend=false   # bootstrap · environments/production · environments/staging
tofu -chdir=infra/<stack> validate
unset TF_DATA_DIR
shellcheck -x infra/modules/environment/cloud-init/*.sh
```

The `TF_DATA_DIR` line is not decoration, and `infra/AGENTS.md` says what it works around.

`tofu plan` and `tofu apply` are **not** on that list: they need a Hetzner API token and they spend money. Both environments are applied and live —
changing `infra/` changes running servers.

Helm chart ([`deploy/`](deploy)). **Read [deploy/AGENTS.md](deploy/AGENTS.md) before touching it.** Everything that renders the chart is safe — it reaches no
cluster and needs no kubeconfig — and these are what `validate-chart.yml` runs:

```bash
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
helm template t deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
helm unittest --strict deploy/charts/event-junkie   # asserts on the rendered chart; the gate that matters
scripts/cluster-assertions.sh                      # and on what each cluster's HelmRelease deploys
```

`helm install`, `upgrade`, `uninstall` and `rollback` are **not** on that list — and neither is `helm install --dry-run`, which resolves the current kubeconfig
context and talks to that cluster. Use `helm template`, or `--dry-run=client` when you specifically need `NOTES.txt`. The chart has never been installed
anywhere: #263 is the first time it runs.

The whole stack on a local Kubernetes — the runtime counterpart to everything above, since `helm template` passing is not evidence that a pod starts:

```bash
scripts/k3d-rehearsal.sh all      # build, install on k3d, assert routing, run a real import, tear down
```

The chart and the images have to agree about which UID they run as, and that is a gate rather than a comment (#448). It reads the `USER` line out of all three
Dockerfiles and compares it with what the chart resolves per component, so a Dockerfile-only change cannot drift away from `values.yaml` silently — which
`helm unittest` cannot catch, because it can only see the chart. It also enforces the **>10000** floor (Trivy KSV-0020/KSV-0021). `validate-chart.yml` runs it:

```bash
scripts/uid-consistency.sh
```

Driven by [`/k3d-rehearsal`](.github/prompts/k3d-rehearsal.prompt.md). It is the only thing here that talks to a Kubernetes cluster, and it passes
`--context k3d-event-junkie` on every call rather than trusting the active one — read `deploy/AGENTS.md` before changing that.

Container images (`events-bff/Dockerfile`, `events-importer/Dockerfile`). The build context is each module's `build/docker`, not the module directory — it is
exactly the extracted layers, which is why neither needs a `.dockerignore`:

```bash
./gradlew :events-bff:bootJarLayers                 # explode the fat jar into build/docker/
docker buildx build -f events-bff/Dockerfile events-bff/build/docker \
  --platform linux/amd64,linux/arm64 --output type=cacheonly          # both arches, no push
docker buildx build -f events-bff/Dockerfile events-bff/build/docker -t event-junkie/bff:dev --load
```

Three rules these files exist under, each of which something else depends on:

- **No builder stage, and no `RUN` that does build work.** Build work in a `RUN` executes target-architecture code and produces architecture-specific output,
  which is what would force a runner per architecture. With the layer extraction in Gradle rather than in the Dockerfile, unlike Spring Boot's reference
  example, one runner emits both platforms — and that is also why the **AOT cache** Spring Boot recommends for Java 25+ is deliberately not used: its output
  is architecture-specific. **The one `RUN` allowed is a named `apk upgrade`** for a base-image CVE the base has not been rebuilt with (#964, #770): it runs
  for arm64 under the emulation the runner already carries, and `events-bff/Dockerfile` states the rule for such a layer and its deletion condition.
- **`USER 10001:10001`, numeric and above 10000.** A named user would need `RUN useradd`. It must match `security.runAsUser` in the chart's `values.yaml`,
  and `scripts/uid-consistency.sh` is what enforces that — a mismatch is a pod that cannot read its own files, which does not look like a values problem from
  the logs. Above 10000 since #448: a UID inside the host's own user range lands as a real account if a container ever escapes its namespace, and nothing maps
  to 10001. Trivy's KSV-0020/KSV-0021 check exactly this.
- **Nothing about the runtime is baked in.** Ports and `JAVA_TOOL_OPTIONS` come from the chart via `SERVER_PORT`, `MANAGEMENT_SERVER_PORT` and the environment.
  A value fixed in the image either gets overridden confusingly or silently wins.

The **frontend** image follows the same shape with a different artefact — `npm run build` produces `dist/`, and the image is nginx plus that directory:

```bash
npm --prefix events-frontend run build
docker buildx build events-frontend -t event-junkie/frontend:dev --load
```

Three things about it that are decisions rather than defaults:

- **`nginxinc/nginx-unprivileged`, not `nginx`.** It listens on 8080 (a non-root process cannot bind 80) and its `nginx.conf` already relocates the pid file
  and every `*_temp_path` into `/tmp`, which is why the container needs exactly one writable path. Replace `conf.d/default.conf` only — rewriting the image's
  `nginx.conf` is how those properties get lost.
- **No `/api` proxy.** The ingress routes `/api` to the BFF and `/` here, so nginx never sees an API request. Running the image standalone therefore gives a
  working site whose API calls 404, and that is expected.
- **`index.html` is `no-cache`, `/assets/` is `immutable`, and a missing asset must 404** rather than fall back to `index.html` — otherwise a stale page asking
  for a deleted bundle gets HTML with a 200 and fails to parse as JavaScript.

**Verify a change by running the image the way the chart will**, which is the check that catches what `docker build` cannot:

```bash
docker run --rm --read-only --tmpfs /tmp -e … event-junkie/bff:dev
docker run --rm --read-only --tmpfs /tmp -p 8080:8080 event-junkie/frontend:dev
```

Local dev environment (used by `/importer-smoke` and `/next-importer`; run with no arguments for the full command list):

```bash
scripts/dev-env.sh status                 # Is the database / importer / bff / frontend up?
scripts/dev-env.sh db-reset               # docker compose down --volumes + fresh Postgres
scripts/dev-env.sh up [service…]          # Start in the background, wait until it answers
scripts/dev-env.sh down [service…] [--db] # Stop service(s) (and optionally the database)
scripts/dev-env.sh seed-all               # Run http/importer/dev-seed.http via ijhttp — scrapes every venue
scripts/dev-env.sh seed-one v.json s.json # Register a single venue + event source, print its slug
scripts/dev-env.sh import <slug>          # Trigger one source's import and poll until it settles
scripts/dev-env.sh snapshot [file]        # Per-source event counts (regression baseline)
scripts/dev-env.sh diff-snapshot a b      # Which sources gained or lost events between two snapshots
scripts/dev-env.sh check <slug>           # Data-quality report for one source
```

`service` is one or more of `importer` (default) · `bff` · `frontend` · `all`, so bare `up` / `down [--db]` behave exactly as before. `up all` brings up the
whole stack; the frontend proxies `/api` to the BFF (`events-frontend/vite.config.ts`), so on its own it renders but every request 502s. The frontend is pinned
with `--strictPort` — a busy port fails loudly instead of Vite quietly moving to the next one.

`up` starts the importer with `app.scheduling.enabled=false` so a smoke test scrapes only the source under test rather than every source whose 24h interval
happens to be due. Pass `--scheduling` to leave it on (that is the configuration in which the scheduler races manual triggers — see ADR-009 on the import
claim). Neither `bootRun` nor this script hot-reloads Kotlin — restart (`down` then `up`) after changing code, or the smoke test runs the previous build. Vite
_does_ hot-reload, so the frontend needs no restart. Runtime artefacts land in `build/dev-env/` (gitignored): `<service>.log`, `<service>.pid`, snapshots.

When launching these from an agent shell, redirect the command's own output (`> file 2>&1 < /dev/null`) — the detached `bootRun`/`vite` process inherits the
tool's stdout pipe and keeps the call hanging long after the script itself has exited.

**Never run Gradle while an import is in flight.** The "does not hot-reload" note above is about _picking up_ your changes; it is not the same as nothing
happening. Both Boot modules carry `spring-boot-devtools` (`developmentOnly`), which watches the classpath — so **any** task that writes classes
(`compileKotlin`, `classes`, `build`, even a single `--tests` run) restarts the running service and **kills every import mid-flight**. Those sources are then
stuck in `RUNNING` forever, because the 30-minute staleness guard only runs under the scheduler and `dev-env.sh up` disables it. The tell in the log is
`restartedMain` next to a suspiciously short `Started EventsImporterApplicationKt in 1.0 seconds (process running for 117.3)`. There is no reset endpoint;
recovery is manual:

```bash
scripts/dev-env.sh psql "UPDATE events.event_source SET status='IDLE', retry_count=0, version=version+1 WHERE status='RUNNING'"
```

then re-trigger those slugs. On a long job — a `--full` re-seed, a before/after diff — compile everything first, restart once, _then_ import, and leave the
build alone until every source has left `RUNNING`.

**A parser fix at a venue whose page has not changed is a 304 forever.** The importer sends the cached `ETag` / `Last-Modified`, and a `Not modified` answer
skips the import on the schedule and on a manual trigger alike, so the fixed parser never runs until the venue edits its page. Do not clear the columns with
`psql`; trigger the one source with `POST /api/admin/event-sources/<slug>/import?force=true` (#1159). The log says
`Fetching source page unconditionally (forced)`, and the run stores the fresh validators, so the next run is conditional again.

**Re-keying a live source collides with its own today-dated rows.** Changing how a scraper builds its `sourceId` — adding the session start time, the occurrence
date, anything — gives every event a new id, so the old rows go stale and the new ones insert. But `EventUpsertService.removeStaleEvents`
deliberately spares **today**: a today-dated row therefore keeps its old id _and_ its slug while its replacement tries to take the same slug, and the insert
collides. **Re-key on a day the venue's programme is dark, or clear that source's rows first** — and check which it is before importing rather than after.
Admiralspalast (2026-08-08) got away with it by luck; Velomax (2026-08-09) was checked and was genuinely dark three weeks out.

**Do not truncate `<service>.log` while the service is running.** `: > build/dev-env/importer.log` looks like the obvious way to get a clean log before a test
import, and it silently breaks every later `grep`: the process keeps its file descriptor at the old offset, so new writes land far into the file and everything
before them is NUL padding. `grep` then treats the file as binary and prints `Binary file … matches`, or **nothing at all with `-c`** — which reads exactly like
"no matches" and is how a real finding gets reported as a clean result. Get a clean log by restarting the service instead (`down` then `up`, which reopens the
file); if one has already been truncated, `grep -a` reads it. **A zero count from a log you truncated is not evidence.**

**Working in a git worktree** (a session started with `claude --worktree`, or any `git worktree add` checkout — see
[docs/WORKTREES.md](docs/WORKTREES.md)). Files and Gradle output are isolated; the local runtime is not.

- **Export `COMPOSE_PROJECT_NAME=event-junkie` before any `bootRun` or `scripts/dev-env.sh up` in a worktree.** Docker Compose names the project after the
  directory containing the `compose.yaml` it is given, and both paths pass the worktree's copy — so without the override the worktree starts a _second_
  Postgres on a new empty volume, which collides with the main checkout on host port `56298` and makes `diff-snapshot` report every existing source as `GONE`.
  With it, the running `event-junkie-postgres-1` container and its seeded data are reused.
- **One stack at a time.** Ports `8081` / `8080` / `5173` are fixed in `application.yaml` and `dev-env.sh`; `IMPORTER_HOST` / `BFF_HOST` only change the URL the
  script polls, not the port the JVM binds. Run `scripts/dev-env.sh down` in the other checkout before `up` here, and remember `bootRun` does not hot-reload —
  whichever worktree started the JVM is the code under test.
- **Never trigger an import while another worktree is importing.** `snapshot` / `diff-snapshot` are per-source counts over the whole shared database, so the
  other session's events land in this session's regression diff.
- **Expect conflicts in the files every importer PR touches**: the count table and moved row in `docs/EVENT_DATA_SOURCES.md` (recount after rebasing rather than
  trusting either side), the alphabetical header list and venue block in `http/importer/dev-seed.http` (a "keep both" resolution silently fuses two blocks —
  rebuild by hand) and the new `EventSource.kt` enum entry. Rebase onto `main`; never merge `main` in. The backlog snapshot is generated into `build/` and is
  not committed, so it never appears in a diff at all.

The **configuration cache** is enabled (`org.gradle.configuration-cache=true` in `gradle.properties`), so repeat builds skip the configuration phase. Every task
above benefits except `dependencyCheckAggregate` — the OWASP plugin's `Aggregate` task reaches for `project.rootProject` / `project.subprojects` at execution
time, which the configuration cache forbids. That task still runs correctly without the flag, but the cache entry is discarded on every invocation and the build
prints a problems report, so pass `--no-configuration-cache` to skip the futile attempt. Both CI workflows that run it already do. Still the case on **13.0.0**;
the upstream fix ([dependency-check-gradle#478](https://github.com/dependency-check/dependency-check-gradle/pull/478)) is still open, so recheck when it lands.

Frontend (`events-frontend/`):

```bash
npm run dev        # Vite dev server
npm run build      # Type-check + production build
npm run test:unit  # Vitest unit tests
npm run test:e2e   # Playwright end-to-end tests
npm run lint       # oxlint + eslint (auto-fix)
npm run format     # oxfmt formatter
```

Java version is managed via SDKMAN (`.sdkmanrc` pins `java=25.0.2-tem`; run `sdk env` to activate). Toolchain target: **Java 25**.

## Automating GitHub with `gh`

**The mechanics of the tool are somebody else's document.** `--json` and `--jq`, the limits that truncate a list silently, `-R`, search versus list, when to
drop to `gh api` — all of that is GitHub's own agent skill, vendored into [`.claude/skills/gh/`](.claude/skills/gh/SKILL.md) from
[`cli/cli`](https://github.com/cli/cli/tree/trunk/skills/gh) so it is present for every contributor rather than only whoever installed it globally. It assumes
`gh` is installed and authenticated. Keeping it current, and the rule that nothing repository-specific may be written into it, are in
[`.claude/skills/gh/VENDORED.md`](.claude/skills/gh/VENDORED.md). [`.github/skills/gh`](.github/skills) is a symlink onto the same directory, so Copilot's
cloud agent finds it at its own documented path without a second copy.

**What follows is the half upstream cannot know**: findings from this repository's own board, rulesets and bulk edits. Each looks like a bug in your script the
first time you hit it. The workflow-file counterparts — fork pull requests, required checks, what CI may write to — are in
[.github/instructions/ci-cd.instructions.md](.github/instructions/ci-cd.instructions.md).

- **A pull request's `mergeable_state` goes stale after a ruleset change, and polling never refreshes it** (2026-08-19). After the `main` ruleset was edited to
  drop a rule that had been blocking #579, the API kept answering `"blocked"` across five polls over three minutes — with every required context green. The
  merge then went through from the web UI, and since `bypass_actors` is `[]` and `current_user_can_bypass` is `"never"`, it cannot have been an override: the
  rules had been satisfied the whole time and only the cached verdict was wrong. GitHub recomputes mergeability lazily, on a pull-request event or a UI view,
  and `gh api …/pulls/<n>` reads the cache rather than triggering the recomputation.

    **So do not diagnose a stale `blocked` as a live rule.** Compare the required contexts against what actually reported first — that is a two-line check and
    it is conclusive:

    ```sh
    gh api repos/OWNER/REPO/rulesets/<id> --jq '.rules[] | select(.type=="required_status_checks") | .parameters.required_status_checks[].context' | sort > /tmp/req
    gh api repos/OWNER/REPO/commits/<sha>/check-runs?per_page=100 --jq '.check_runs[] | select(.conclusion=="success") | .name' | sort -u > /tmp/got
    comm -23 /tmp/req /tmp/got     # empty means nothing required is missing
    ```

    If that comes back empty, open the pull request in a browser or attempt the merge rather than hunting for a rule that is no longer there.

- **Pace bulk mutations.** GitHub's _secondary_ rate limit bites long before the documented hourly one. A `sleep 0.45` between calls carried 255 PR edits and
  146 issue creations with zero failures; without it, a few hundred back-to-back writes reliably trip it.
- **`gh issue create` and `gh issue edit` do not share a label flag.** Create takes `--label`; edit takes `--add-label` / `--remove-label`. One argument list
  for both works perfectly on creates and dies on the first update — invisible until something already exists. And an update must reconcile labels in _both_
  directions: `--add-label` alone lets a removed label survive forever with nothing reporting the drift.
- **Project view grouping and sorting cannot be set through the API.** `ProjectV2ViewConfigurationInput` exposes only `visibleFieldIds`. Names, layouts and
  filters are scriptable; the arrangement is a manual UI step. (Still outstanding for the Event Junkie board.)
- **gitleaks fires on `key:` with a high-entropy value.** A YAML front-matter field named `key` tripped the `generic-api-key` rule on 1 file out of 146 —
  intermittent by nature, since it depends on the value's entropy. Prefer `slug`, `id` or `name` for identifier fields. The existing `.gitleaks.toml` allowlist
  is for the scraper fixture tree, and widening it costs real scanning coverage.
- **A cautious first run pays for itself.** `--limit 5`, inspect, then continue. That is what turned the `gh issue edit` bug into a five-issue problem instead
  of a 146-issue one.

## The Backlog — GitHub Issues

**The backlog is [GitHub Issues](https://github.com/enorm-labs/event-junkie/issues), not a file.** `TODO.md` no longer exists.

**Read a generated snapshot; write through `gh`.** `scripts/generate-backlog-snapshot.sh` renders every open issue into `build/BACKLOG.md` — grouped by
milestone, with type, area, size and blocking state per row. Consulting it is then a local file read: cheap, grep-able, no network round trip per question.

**Regenerate it before you rely on it**, and never edit it. It is written into `build/`, which is gitignored, so it is never committed and never appears in a
diff — it is exactly as current as the last time someone ran the script, and its header carries the timestamp so you can tell.

```sh
scripts/generate-backlog-snapshot.sh                # refresh it first — one gh call
grep -i 'heimathafen' build/BACKLOG.md              # is this already tracked?
gh issue list --label importer --state open         # when you need live state
gh issue view 313                                   # the full body, including its Links footer
```

_(This was briefly a committed file refreshed by a workflow. That cannot work here: the `main` ruleset requires every change to arrive by pull request, only an
OrganizationAdmin may bypass it, and GitHub refuses the Actions bot as a bypass actor. The workflow failed on its first run and the committed copy went stale
within the hour — so the file moved to `build/` and the workflow was deleted.)_

**Filing something.** Use `/new-issue`, which checks for a duplicate first and picks the right form. By hand,
`.github/ISSUE_TEMPLATE/` has 🛠 Task, ✨ Feature, 🔍 Importer / data defect, ⚖️ Decision and 🧭 Epic. The importer-defect form is the one to reach for after a
smoke test or a data-quality audit — it asks the questions those findings need, including **whether the fix requires a `--full` re-seed**, which is usually the
difference between a one-hour change and a one-day one.

**Where a finding goes** — the same rule as before, with a new destination:

| Finding                                                                                           | Goes to                                                 |
| ------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| A defect with a known repair — we lose or mangle data the source _did_ publish                    | **An issue** (🔍 Importer / data defect)                |
| An accepted limitation — the venue never publishes it, or the parser makes a deliberate trade-off | **That scraper's KDoc**, next to the code it constrains |
| A choice that must be made before work can start                                                  | **An issue** (⚖️ Decision), labelled `needs-decision`   |

**The label and field split.** Intrinsic properties of the work are **labels** — `area:*`, `size:*`, plus `importer` and `documentation`. Planning state lives
in the **[project board](https://github.com/orgs/enorm-labs/projects/1)** as Status and Priority fields, because priority churns and label churn is noise. Issue
_type_ is a GitHub issue type (Task / Bug / Feature), not a label — do not add a `type:` label.

Three labels name _why_ something cannot start: `blocked` (another issue), `needs-decision` (a choice), `needs-deployment` (a live origin). **The last is not
neglected work** — it is work that cannot exist yet, and it is labelled so it stops reading as neglect.

**Milestones.** `v0.2 — Deployable` → `v0.3 — Launch-ready` → `v1.0 — Go-live` are the path to launch; `Phase 2/3/4` are post-launch buckets with no due date.
No milestone means unscheduled. Direction and the reasoning behind the phases stay in [docs/VISION_ROADMAP_IDEAS.md](docs/VISION_ROADMAP_IDEAS.md).

**Closing.** Put `Closes #NNN` in the **PR body**, on its own line. This repo allows only **Rebase and merge** — squash and merge commits are both disabled — so
commit messages are replayed onto `main` as written, and a closing keyword in one of them would work too. The PR body is still the right home: it is one line to
fix when the issue number changes, whereas the same line in a commit means rewriting history, and it survives the amending and rebasing a branch goes through
during review. Use `Closes` rather than `Fixes`/`Resolves`, one line per issue.

Give the PR the issue's milestone as well. Every closed PR here carries one — the 255 that predate the tracker were backfilled into `Phase 0 — Foundation` — and
a PR without one is the exception that makes the milestone view stop meaning anything.

## Key Files

| Purpose                                     | Path                                                                                                                              |
| ------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------- |
| Root build config & shared versions         | `build.gradle.kts`                                                                                                                |
| Plugin versions & module includes           | `settings.gradle.kts`                                                                                                             |
| Gradle daemon JVM args                      | `gradle.properties`                                                                                                               |
| Dev database (Postgres)                     | `compose.yaml`                                                                                                                    |
| Detekt rule overrides                       | `detekt.yml`                                                                                                                      |
| OWASP CVE false-positive suppressions       | `owasp-suppressions.xml`                                                                                                          |
| CI: backend build & test                    | `.github/workflows/build-backend.yml`                                                                                             |
| CI: frontend build & test                   | `.github/workflows/build-frontend.yml`                                                                                            |
| CI: dependency review (PR)                  | `.github/workflows/dependency-review.yml`                                                                                         |
| CI: dependency graph submission             | `.github/workflows/dependency-submission.yml`                                                                                     |
| CI: nightly OWASP scan                      | `.github/workflows/dependency-check-scheduled.yml`                                                                                |
| CI: nightly scan of deployed images         | `.github/workflows/image-scan-scheduled.yml` — a published tag, both arches; thresholds match release.yml                         |
| CI: quarterly restore-drill reminder        | `.github/workflows/restore-drill-reminder.yml` — opens the drill as an assigned issue                                             |
| CI: blocker issue for a red publish         | `.github/workflows/publish-failure-issue.yml` — one issue per red streak on `main`, closed by the next green publish              |
| CI: credential expiry reminder              | `.github/workflows/credential-expiry-reminder.yml` — dates live in the workflow, mirrored in docs/CREDENTIALS.md §2               |
| CI: PR labelling                            | `.github/workflows/label-pr.yml`                                                                                                  |
| CI: OpenTofu fmt/validate + ShellCheck      | `.github/workflows/validate-infra.yml`                                                                                            |
| CI: workflow lint + security audit          | `.github/workflows/validate-workflows.yml`; suppressions in `zizmor.yml`                                                          |
| CI: Helm lint/render/assertions             | `.github/workflows/validate-chart.yml`                                                                                            |
| CI: Markdown formatting                     | `.github/workflows/validate-docs.yml`                                                                                             |
| CI: build, scan and publish to GHCR         | `.github/workflows/release.yml` — the only workflow that pushes anything; it does not deploy                                      |
| CI: deployment records from Flux            | `.github/workflows/deployment-status.yml` — writes the GitHub deployment; the cluster triggers it, not a merge                    |
| Version scheme (one number, 4 files)        | `scripts/version.sh`; `gradle.properties` is the source of truth — docs/DEVELOPMENT.md §Versions                                  |
| Snapshot versions must ORDER (#455)         | `scripts/version-test.sh` — asserted against Helm's own solver; a format check would not catch it                                 |
| Markdown formatting                         | `scripts/format-markdown.sh` + `.oxfmtrc.json` — Markdown only, and the scope is load-bearing                                     |
| Brand artwork carries outlined glyphs       | `scripts/outline-text.sh` — a `<text>` logo renders in a fallback face silently; fontTools is pinned for the same reason oxfmt is |
| README screenshots, and when they rot       | `docs/screenshots/` — dated, because nothing else signals staleness; retake on design changes, never on data changes              |
| Trivy waivers                               | `.trivyignore` — empty on purpose; an entry needs a reason and a date                                                             |
| Chart and images agree about the UID        | `scripts/uid-consistency.sh` — reads the three Dockerfiles' `USER` and the chart; enforces the >10000 floor                       |
| What each cluster would deploy              | `scripts/deployed-versions.sh` — reproduces Flux's selection; no cluster and no credential needed                                 |
| Whether the two node pins are current       | `scripts/upstream-node-pins.sh` — k3s and wal-g against upstream; `node-pin-reminder.yml` runs it weekly                          |
| Whether a scanner still covers as much      | `scripts/scan-coverage.sh` + `scripts/scan-coverage-baseline.txt` — denominators, not just exit codes                             |
| Infrastructure as code (OpenTofu)           | `infra/` — read `infra/AGENTS.md` first; `bootstrap/` is applied, `environments/` is not                                          |
| Shared MCP servers                          | `.mcp.json` — `opentofu`, the hosted registry lookup; no key, one approval per contributor                                        |
| Cloud-init for the Hetzner nodes            | `infra/modules/environment/cloud-init/`                                                                                           |
| Helm chart (bff · importer · frontend)      | `deploy/charts/event-junkie/` — read `deploy/AGENTS.md` first; exercised on k3d, never on a real cluster                          |
| Backend container images                    | `events-bff/Dockerfile`, `events-importer/Dockerfile` — no build-work `RUN`, context is each module's `build/docker`              |
| Frontend container image                    | `events-frontend/Dockerfile` + `events-frontend/docker/nginx.conf` — nginx on 8080, context is the module                         |
| Chart assertions                            | `deploy/charts/event-junkie/tests/*_test.yaml` (helm-unittest) + `scripts/cluster-assertions.sh`                                  |
| Release notes categories                    | `.github/release.yml`                                                                                                             |
| Dependabot config                           | `.github/dependabot.yml` — six ecosystems; read it with `renovate.json5` before calling anything unwatched                        |
| Renovate config                             | `.github/renovate.json5` — Flux, cluster images, pre-commit, Gradle wrapper. An allow-list, so it cannot collide with Dependabot  |
| The dependency-update boundary              | `docs/adr/ADR-024_DEPENDENCY_UPDATE_BOUNDARY.md` — which mechanism owns what, and what nothing may propose                        |
| Commit message prompt                       | `.github/prompts/commit-message.prompt.md`                                                                                        |
| Squash commit message prompt                | `.github/prompts/squash-commit-message.prompt.md`                                                                                 |
| Open PR prompt                              | `.github/prompts/open-pr.prompt.md`                                                                                               |
| Compact comments prompt                     | `.github/prompts/compact-comments.prompt.md`                                                                                      |
| Vendored Simplified Technical English skill | `.claude/skills/asd-ste100/`                                                                                                      |
| Vendored GitHub CLI skill                   | `.claude/skills/gh/` — upstream `cli/cli`; see its `VENDORED.md` before touching it                                               |
| Copilot's view of both vendored skills      | `.github/skills/` — one directory symlink each into `.claude/skills/`; never a copy                                               |
| Skill and command parity check              | `scripts/skill-parity.sh`                                                                                                         |
| Code review prompt                          | `.github/prompts/code-review.prompt.md`                                                                                           |
| Security report prompt                      | `.github/prompts/security-report.prompt.md`                                                                                       |
| Security triage prompt                      | `.github/prompts/security-triage.prompt.md` — its `--unattended` section is what `agent-security.yml` runs                        |
| Agentic workflow (security)                 | `.github/workflows/agent-security.yml` — nightly and on a red publish, opens a PR, dismisses nothing                              |
| Agentic workflow (refactor)                 | `.github/workflows/agent-refactor.yml` — fenced away from shared normalization                                                    |
| Agentic workflow (comments)                 | `.github/workflows/agent-comments.yml` — whole tree nightly, capped at twelve files per PR                                        |
| Plausibility check prompt                   | `.github/prompts/plausibility-check.prompt.md` — the site against the venues' pages, read-only                                    |
| Agentic workflow (plausibility)             | `.github/workflows/agent-plausibility.yml` — nightly report, opens nothing and files nothing                                      |
| Refactor prompt                             | `.github/prompts/refactor.prompt.md`                                                                                              |
| Documentation currency prompt               | `.github/prompts/update-docs.prompt.md`                                                                                           |
| Agentic workflow (documentation)            | `.github/workflows/agent-docs.yml` — corrects facts, rewrites no argument, never touches an ADR                                   |
| ADR-authoring prompt                        | `.github/prompts/write-adr.prompt.md`                                                                                             |
| Shared domain module marker                 | `events-core/src/.../EventsCoreModule.kt`                                                                                         |
| Domain data classes                         | `events-core/src/.../artist/`, `event/`, `genretag/`, `promoter/`, `venue/`                                                       |
| Price normalization utility                 | `events-core/src/.../event/MoneyExtensions.kt`                                                                                    |
| Initial DB migration                        | `events-importer/src/main/resources/db/migration/V001__create_initial_schema.sql`                                                 |
| Global exception handler                    | `events-importer/src/.../GlobalExceptionHandler.kt`                                                                               |
| Slug generator utility                      | `events-importer/src/.../slug/SlugGenerator.kt`                                                                                   |
| Genre normalizer utility                    | `events-importer/src/.../genretag/GenreNormalizer.kt`                                                                             |
| Shared scraping utilities                   | `events-importer/src/.../scraper/ScrapingExtensions.kt`                                                                           |
| Shared date/time parsing                    | `events-importer/src/.../scraper/DateParsingExtensions.kt`                                                                        |
| Event-type classification                   | `events-importer/src/.../scraper/EventTypeMapping.kt`                                                                             |
| Artist-name resolution                      | `events-importer/src/.../scraper/ArtistNameMapping.kt`                                                                            |
| Event field-level mapping                   | `events-importer/src/.../scraper/EventFieldMapping.kt`                                                                            |
| WebFlux Pageable resolver config            | `events-importer/src/.../WebFluxConfiguration.kt`                                                                                 |
| Stable-sort Pageable resolver               | `events-importer/src/.../StableSortPageableArgumentResolver.kt` (duplicated in `events-bff`)                                      |
| Base integration test class                 | `events-importer/src/test/.../BaseControllerTest.kt`                                                                              |
| Full lifecycle integration test             | `events-importer/src/test/.../event/FullLifecycleIntegrationTest.kt`                                                              |
| Testcontainers setup (BFF)                  | `events-bff/src/test/.../PostgresTestcontainersConfiguration.kt`                                                                  |
| Testcontainers setup (importer)             | `events-importer/src/test/.../PostgresTestcontainersConfiguration.kt`                                                             |
| Modularity verification (BFF)               | `events-bff/src/test/.../ModularityTests.kt`                                                                                      |
| Modularity verification (importer)          | `events-importer/src/test/.../ModularityTests.kt`                                                                                 |
| Modularity verification (core)              | `events-core/src/test/.../ModularityTests.kt`                                                                                     |
| ADR: Reactive stack                         | `docs/adr/ADR-001_REACTIVE_STACK.md`                                                                                              |
| ADR: R2DBC query derivation limits          | `docs/adr/ADR-002_R2DBC_QUERY_DERIVATION.md`                                                                                      |
| ADR: Entity/domain separation               | `docs/adr/ADR-003_ENTITY_DOMAIN_SEPARATION.md`                                                                                    |
| ADR: Dedicated database schema              | `docs/adr/ADR-004_DEDICATED_DATABASE_SCHEMA.md`                                                                                   |
| ADR: Migrations owned by importer           | `docs/adr/ADR-005_MIGRATIONS_OWNED_BY_IMPORTER.md`                                                                                |
| ADR: Spring Modulith                        | `docs/adr/ADR-006_SPRING_MODULITH.md`                                                                                             |
| ADR: Web scraping strategy                  | `docs/adr/ADR-007_WEB_SCRAPING_STRATEGY.md`                                                                                       |
| ADR: Import job scheduling                  | `docs/adr/ADR-008_IMPORT_JOB_SCHEDULING.md`                                                                                       |
| ADR: Optimistic locking (event src)         | `docs/adr/ADR-009_OPTIMISTIC_LOCKING_EVENT_SOURCE.md`                                                                             |
| ADR: Frontend styling framework             | `docs/adr/ADR-010_FRONTEND_STYLING_FRAMEWORK.md`                                                                                  |
| ADR: Event-calendar library                 | `docs/adr/ADR-011_CALENDAR_LIBRARY.md`                                                                                            |
| ADR: Cloud platform & hosting               | `docs/adr/ADR-012_CLOUD_PLATFORM.md`                                                                                              |
| ADR: Localisation (English + German)        | `docs/adr/ADR-013_LOCALISATION.md`                                                                                                |
| ADR: Rendering strategy (SPA/SSG/SSR)       | `docs/adr/ADR-014_RENDERING_STRATEGY.md`                                                                                          |
| ADR: Observability stack                    | `docs/adr/ADR-015_OBSERVABILITY_STACK.md`                                                                                         |
| ADR: GitOps delivery (Flux, pull)           | `docs/adr/ADR-016_GITOPS_DELIVERY.md`                                                                                             |
| ADR: JRE base image (Liberica/Alpine)       | `docs/adr/ADR-017_JRE_BASE_IMAGE.md`                                                                                              |
| ADR: Probe semantics (readiness/liveness)   | `docs/adr/ADR-018_PROBE_SEMANTICS.md`                                                                                             |
| ADR: Venue image delivery (cached)          | `docs/adr/ADR-019_VENUE_IMAGE_DELIVERY.md` — cache in a bucket, not hotlink. Not implemented, #283 blocks it                      |
| ADR: Image processing (imgproxy)            | `docs/adr/ADR-020_IMAGE_PROCESSING.md` — derivatives at import time, never on the request path                                    |
| ADR: Public site monitoring                 | `docs/adr/ADR-021_PUBLIC_SITE_MONITORING.md` — a Better Stack monitor polls every three minutes, from outside the cluster         |
| ADR: Shared cluster base                    | `docs/adr/ADR-022_SHARED_CLUSTER_BASE.md` — `deploy/clusters/base/` holds what does not differ; each cluster patches one field    |
| ADR: Operator authentication                | `docs/adr/ADR-023_OPERATOR_AUTHENTICATION.md` — the admin API stays unroutable. A Traefik middleware when a surface is deployed   |
| ADR: Dependency update boundary             | `docs/adr/ADR-024_DEPENDENCY_UPDATE_BOUNDARY.md` — three mechanisms, and which one owns what. Read before adding a fourth         |
| ADR: Release number from the commits        | `docs/adr/ADR-025_RELEASE_VERSION_FROM_COMMITS.md` — a `feat` is a minor, a break a major. The cut refuses less                   |
| Plan: Hetzner + k3s setup, go-live          | `docs/ops/PLATFORM_SETUP.md`                                                                                                      |
| Releasing & deploying, end to end           | `docs/ops/RELEASING.md` — the diagram; ADR-016 has the reasoning                                                                  |
| Bootstrapping a cluster, once               | `docs/ops/CLUSTER_BOOTSTRAP.md` — ordered runbook, first run 2026-08-13; traps table at the bottom                                |
| Connecting to a running cluster             | `docs/ops/CLUSTER_ACCESS.md` — tunnel, kubeconfig, contexts, k9s. Read-only; nothing in it changes anything                       |
| Upgrading k3s on a running node             | `docs/ops/K3S_UPGRADE.md` — in place, not a rebuild; the Traefik check is the one that matters                                    |
| Alerting from outside the cluster           | `docs/ops/HEALTHCHECKS.md` — healthchecks.io dead-man's switches. Ping URLs are credentials and live only on the node             |
| Secrets, and the SOPS plan                  | `docs/ops/SECRETS.md` — three hand-made objects today; the age private key never enters this repository                           |
| Flux resources (one dir per cluster)        | `deploy/clusters/` — read `deploy/AGENTS.md` first; the semver range is on the OCIRepository                                      |
| Plan: footer, legal pages, versioning       | `docs/LEGAL.md`                                                                                                                   |
| Backlog snapshot generator                  | `scripts/generate-backlog-snapshot.sh` → `build/BACKLOG.md` (generated, not committed)                                            |
| Issue board helper                          | `scripts/issue-board.sh` — Status and Priority are project fields, not labels                                                     |
| Frontend entry point                        | `events-frontend/src/main.ts`                                                                                                     |
| IntelliJ HTTP Client requests               | `http/importer/` (admin) and `http/bff/` (public read) `.http` files + shared `http/http-client.env.json`                         |
| Venue coordinates, and checking them        | `scripts/geocode-venues.py` + `http/google/` (detector) and `http/osm/` (the ODbL source) — read the script's docstring           |
| Local dev environment control script        | `scripts/dev-env.sh` (start/stop the stack, seed sources, trigger imports, inspect + diff the data)                               |
| Performance tests (k6)                      | `perf/` — `smoke.js` · `load.js` · `spike.js`, endpoints in `perf/lib/api.js`                                                     |
