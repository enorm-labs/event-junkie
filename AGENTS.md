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
| [Build & Dev Commands](#build--dev-commands)                                      | Running anything locally, and the local traps                         |
| [Project skills](#project-skills)                                                 | Which slash command does what                                         |
| [Automating GitHub with `gh`](#automating-github-with-gh)                         | Scripting issues, pull requests or the board                          |
| [The Backlog](#the-backlog--github-issues)                                        | Filing, claiming or closing an issue                                  |
| [Key Files](#key-files)                                                           | The files that carry a rule, and the documents that carry a runbook   |

**The rest is path-scoped and loads itself.** The detail that only matters for one kind of file lives in [`.github/instructions/`](.github/instructions), one
file per topic, each declaring the paths it applies to. Claude Code reads them through [`.claude/rules/`](.claude/rules) and GitHub Copilot reads them
directly; both pull a file into context when you touch a file it matches. An agent that reads only this file should follow the links.

| Rule file                                                                                                                                                      | Loads when you touch                                                    | Covers                                                                          |
| -------------------------------------------------------------------------------------------------------------------------------------------------------------- | ----------------------------------------------------------------------- | ------------------------------------------------------------------------------- |
| [architecture](.github/instructions/architecture.instructions.md)                                                                                              | `events-core/`, `events-bff/`, `events-importer/`                       | The reactive stack, the schema, migrations, DTOs, metrics                       |
| [kotlin](.github/instructions/kotlin.instructions.md)                                                                                                          | `*.kt`, `*.kts`, `gradle.properties`, `detekt.yml`                      | Idioms, where versions live, ktlint · detekt · Kover                            |
| [logging](.github/instructions/logging.instructions.md)                                                                                                        | `events-core/`, `events-bff/`, `events-importer/`                       | Levels, exception as argument, MDC vs payload, the three places a field lives   |
| [python](.github/instructions/python.instructions.md)                                                                                                          | `*.py`, `ruff.toml`                                                     | Standard library only, ruff, and tests that are plain scripts                   |
| [comments](.github/instructions/comments.instructions.md)                                                                                                      | Every source language                                                   | Few, short, about _why_ — and what lint already enforces                        |
| [documentation](.github/instructions/documentation.instructions.md)                                                                                            | `docs/**/*.md`                                                          | Simplified Technical English: how the sentences are written                     |
| [markdown](.github/instructions/markdown.instructions.md)                                                                                                      | `*.md`                                                                  | oxfmt, its pinned scope, and why it runs twice                                  |
| [vue](.github/instructions/vue.instructions.md)                                                                                                                | `events-frontend/` `*.vue`, `*.css`                                     | SFC structure, Tailwind v4 + shadcn-vue, accessibility                          |
| [design](.github/instructions/design.instructions.md)                                                                                                          | `events-frontend/src/`                                                  | The tokens, the type scale, the spacing, and the forbidden list                 |
| [testing](.github/instructions/testing.instructions.md)                                                                                                        | backend `src/test/`, frontend `e2e/`, `__tests__/`                      | JUnit + Testcontainers, and Vitest + Playwright                                 |
| [kubernetes](.github/instructions/kubernetes.instructions.md)                                                                                                  | `deploy/**/*.yaml`                                                      | Audited API versions, the YAML boolean trap, PSS `restricted`                   |
| [ci-cd](.github/instructions/ci-cd.instructions.md)                                                                                                            | `.github/workflows/`, `dependabot.yml`, `renovate.json5`, `release.yml` | Every workflow, the required checks, the Dependabot/Renovate boundary, fork PRs |
| Each rule file carries the same globs twice — `applyTo:` for Copilot and `paths:` for Claude Code, which reads it through a `.claude/rules/` symlink —         |
| and `scripts/rules-parity.sh` fails when they drift, when a glob matches no tracked file, or when a rule is missing from the table above. **A rule body has to |
| be inline**: an `@` pointer inside a rule file is expanded at launch whatever `paths:` says, so the scoping silently buys nothing.                             |

**Sibling files, none of them optional in their own subtree:** [`infra/AGENTS.md`](infra/AGENTS.md) opens with the OpenTofu commands that must never be run ·
[`deploy/AGENTS.md`](deploy/AGENTS.md) with the difference between rendering the chart and installing it · [`events-frontend/AGENTS.md`](events-frontend/AGENTS.md)
covers the SPA. A subtree's `AGENTS.md` loads when an agent reads a file under it, so guidance that only matters to one module goes next to the module.

**There is no `CLAUDE.md` here, on purpose.** Claude Code 2.1.277 and later reads `AGENTS.md` as the project instructions when no `CLAUDE.md` exists. Do not
commit a `CLAUDE.md` or a `CLAUDE.local.md`: either one stops Claude Code reading `AGENTS.md` unless **Project instructions** in `/config` is
`claude-md-and-agents-md`. `.gitignore` covers both names, so a session that cannot read `AGENTS.md` directly (Bedrock, Vertex, `disableAllHooks`) can keep a
local `CLAUDE.md` holding just `@AGENTS.md`.

## Agent Instructions

- **Git without a pager**: `git --no-pager <command>` or `GIT_PAGER=cat`, on every command that may page (`log`, `diff`, `show`, `branch`).
- **ktlint auto-format first**: on a ktlint finding run `./gradlew ktlintFormat`; edit by hand only what it cannot fix.
- **Reformatting is intentional — keep it.** Files are reformatted on purpose (IDE reformat-on-save, `ktlintFormat`, `npm run format`). Never revert,
  re-fetch or "restore" a file because its whitespace, wrapping or attribute order changed, and never reformat back. Review the content with
  `git --no-pager diff -w`; whitespace-only churn needs no report. This includes the scraper HTML fixtures under `events-importer/src/test/resources/scraper/`:
  Jsoup ignores indentation, so a reformatted snapshot is still valid and never on its own a reason to re-capture. The one caveat: whitespace _between inline
  elements_ changes the text Jsoup returns (`<b>a</b><b>b</b>` is `ab`; with a newline, `a b`). If a reformat fails a scraper test, that is a finding —
  **raise it with the user**; do not revert the file or loosen the assertion.
- **Build verification**: `./gradlew clean build` after a backend change. Skip it when only `.md` or `events-frontend/` files changed; a Markdown change still
  runs `scripts/format-markdown.sh` ([markdown.instructions.md](.github/instructions/markdown.instructions.md)).
- **Write a plan to `temp/`** (gitignored), as Markdown, named `temp/<issue>-<slug>.md` or `temp/<topic>.md`. The same for an audit or a draft anyone will read.
  A plan in the tree becomes documentation nobody updates; a plan only in the terminal is gone at the next compaction. Format it by name —
  `scripts/format-markdown.sh temp/<file>.md` — because nothing else reaches `temp/`, and a plan pasted unformatted into an issue is the usual tell.
  **Delete it when the work lands.**
- **No unsolicited git commits, pushes or rebases.** Only when the user asks.
- **Amend by default on a feature branch, and land one commit.** Once the branch carries a commit of yours, the next change amends it; push with
  `git push --force-with-lease`, never bare `--force`. `main` allows only **Rebase and merge**, so every branch commit lands on `main` as written. A branch
  that ends up with several commits is squashed before merging — [`/squash-commit-message`](.github/prompts/squash-commit-message.prompt.md) writes the
  message. **Amending changes the diff, so rewrite the message, the PR title and the PR body with it.** Keep commits separate when the user asks, or when a
  reviewer has already commented on one.
- **Documentation describes the current state. Replace, never append.** Present tense; rewrite the passage, no "Update:" notes, no dated banners, no new
  section beside the old one — two passages for successive states is a defect. Delete completed phases and settled decisions; git and the issue hold them.
  Reasoning goes below the instructions, in a final `## Background and history`, or into an ADR, or nowhere. **Every document over ~150 lines opens with
  `## The short version`** — commands and the two or three rules that catch most changes, no prose. A status banner stays only while it warns of something
  _currently_ untrue ([docs/LEGAL.md](docs/LEGAL.md)'s "not signed off") and goes the moment that stops. A closed item on a list of open questions is deleted,
  not annotated. An issue or ADR reference is a pointer, not a summary: `see #540`, and stop.
- **Documentation under `docs/` is written in Simplified Technical English.** One idea per sentence, 25 words at most, active voice, no semicolons; the whole
  rule and the `asd-ste100` skill are in [documentation.instructions.md](.github/instructions/documentation.instructions.md). **Keep every hedge at its
  original strength** — an STE rewrite goes wrong by shortening _may have failed_ into _failed_ (#733).
- **A red `release.yml` on `main` blocks every release, and the cause is usually not the change that landed** — the image scan gates on base-image findings.
  Look at the latest run before cutting; `cut-release.yml` refuses a red one. [RELEASING.md § Publishing is blocked](docs/ops/RELEASING.md#publishing-is-blocked).
- **Logging is part of the change.** Before calling a backend change done, ask what someone on the cluster with one `sourceslug` or one `requestid` would
  need to see: a caught-and-continued path gets a `WARN` with the exception as the argument, a call out of the process gets its outcome, a scheduled pass
  gets start, result and failure. [logging.instructions.md](.github/instructions/logging.instructions.md) has the levels and the three places a field name
  lives. A change to _what_ is logged is also a privacy change — see [Privacy & GDPR](#privacy--gdpr--re-check-when-infrastructure-or-features-change).
- **Correct the docs in the same change that makes them wrong.** The document to fix is the one a reader would reach for, which is usually not the one you
  were editing.
- **ADR numbers are claimed by writing the ADR, never by planning one.** A "needs ADR-0NN" reservation is not honoured: the next ADR written takes that
  number and the reference silently points elsewhere — it happened twice. Refer to a future ADR by title only and take the next free number from `docs/adr/`
  at the moment you create the file.
- **`gh` is a prerequisite** (`brew install gh`, `gh auth login`). How to drive it is a vendored skill, [`.claude/skills/gh/`](.claude/skills/gh/SKILL.md);
  what it cannot know about this repository is in [Automating GitHub with `gh`](#automating-github-with-gh).
- **Library docs: ask `context7` first, and read the release notes on a bump.** The pinned versions (Tailwind 4, Vue Router 5, TypeScript 6, Spring Boot 4)
  postdate most of what a model remembers — a v2-era Tailwind answer writes `tailwind.config.js` into a project that has none.
  [`docs/LINKS.md` § 10](docs/LINKS.md#10-stack-reference-documentation) pairs every framework with its release notes.

## Privacy & GDPR — re-check when infrastructure or features change

The privacy notice (`/legal/privacy`) and the imprint describe **what this system actually does**, in two documents each — `PrivacyView.en.vue` and
`PrivacyView.de.vue` under `events-frontend/src/views/legal/`, German authoritative — and they stay correct only while reality matches. The changes that
break them do not look like privacy work. **If your change is in a category below, say so in the PR description and update [docs/LEGAL.md](docs/LEGAL.md) §7
plus the privacy page in the same PR.**

**Infrastructure and operations**

- A new or changed hosting, CDN, WAF, DNS, mail, backup or object-storage provider — each is a processor that must be _named_, needs an Art. 28 DPA, and a
  transfer mechanism outside the EU/EEA. [ADR-012](docs/adr/ADR-012_CLOUD_PLATFORM.md) leaves **one processor, Hetzner**. `INFRASTRUCTURE_IS_PROPOSED` stays
  `true` until §5 of both notices has been checked against what runs (docs/LEGAL.md §14).
- Log content, log retention or IP handling — the notice states a retention period; it must be the real one.
- Monitoring, error tracking, uptime checks, APM or a metrics backend that receives request or user data.
- A staging or preview environment reachable from the internet. The build's `robots.txt` allows all crawlers and its `sitemap.xml` names production, so
  override both per environment.

**Features**

- **Anything stored on the visitor's device** — cookie, `localStorage`, `sessionStorage`, IndexedDB, Cache API. § 25 TDDDG covers _storage on terminal
  equipment_. Today every stored item is strictly necessary, so **no consent banner is required**; the first non-essential item makes one mandatory and is a
  product decision. **Escalate rather than implement.**
- **Any third-party resource loaded by the browser** — font, script, iframe, map, embed, hotlinked image. Each transmits the visitor's IP. Fonts are
  self-hosted (`@fontsource-variable/geist`) for this reason.
- **Any outbound call from the frontend** to a domain we do not operate. LEGAL.md §4.1 says why the footer's version does not come from the GitHub API.
- **Accounts, login, sessions, newsletter, contact form, comments, favourites, notifications** — each needs its own legal basis, retention and deletion route.
- **New personal data in the domain model.** Artist names are already personal data (LEGAL.md §7.3). Contact details, social handles, photographs of
  identifiable people or user content extend that materially — and `LEGAL.md` §7.3a lists the categories the Hetzner AVV covers; **a category not on that
  list is outside the agreement**. Update §7.3a and re-check the AVV in the same change.
- **Analytics of any kind**, self-hosted and cookieless included — still processing, still needs a basis and a notice entry.

**Commercial changes** — ads, affiliate links, sponsorships, donations, paid features — also change the § 5 DDG imprint analysis.

When in doubt, flag it in the PR. Raising it costs a sentence; missing it is a legal defect on a public site.

## Project Overview

Event Junkie discovers music events in Berlin. A **Gradle multi-project build** (root `settings.gradle.kts`) plus a standalone frontend:

- **`events-core`** — shared domain model, no Boot app; `java-library` + `java-test-fixtures` (`src/testFixtures/`); `api()` scope on
  `spring-modulith-starter-core`. Domain classes by feature: `artist/`, `genretag/`, `promoter/`, `venue/`. The event path has none, and `event/` holds the enums and the money scale (ADR-003).
- **`events-bff`** — the public read API (Spring Boot 4 + WebFlux + R2DBC), port `8080`.
- **`events-importer`** — imports events from venue sites (Boot 4 + WebFlux + R2DBC + Flyway), port `8081`. Owns every migration under
  `src/main/resources/db/migration/`.
- **`detekt-rules`** — this repository's own detekt rules, on every module's `detektPlugins` classpath, configured under the `event-junkie` key in
  `detekt.yml`. Ships nothing, so it is out of Kover, the licence report and the OWASP scan; each exclusion sits next to its reason in `build.gradle.kts`.
- **`events-frontend`** — Vue 3 SPA (Vite 8, TypeScript 6, Vue Router), managed by npm, not Gradle. Node `>=24.15.0`: a **patch** floor forced by jsdom 30;
  `events-frontend/AGENTS.md` records both moves.

## Build & Dev Commands

```bash
./gradlew clean build          # all modules: tests, ktlint, detekt, Kover
./gradlew :events-bff:bootRun  # Postgres starts via compose.yaml; :events-importer:bootRun likewise
./gradlew ktlintFormat         # then ktlintCheck
./gradlew detekt detektMain detektTest   # CI runs all three; only the first is syntax-only
./gradlew koverLog             # coverage per module
./gradlew dependencyCheckAggregate --no-configuration-cache   # OWASP; the plugin forbids the cache, so skip the futile attempt
scripts/dev-env.sh             # the local stack; no arguments prints every command
```

Java comes from SDKMAN (`.sdkmanrc`, `sdk env`); target **Java 25**. Infra, chart, k3d, container images, k6 and the frontend commands are in
[docs/DEVELOPMENT.md](docs/DEVELOPMENT.md); the safe-versus-never lists are in [infra/AGENTS.md](infra/AGENTS.md) and [deploy/AGENTS.md](deploy/AGENTS.md).
`scripts/k3d-rehearsal.sh` is the only thing here that talks to a Kubernetes cluster, and it passes `--context k3d-event-junkie` on every call.

**The local stack, as an agent meets it** (`/importer-smoke` and `/next-importer` drive it):

- `scripts/dev-env.sh up` starts the importer with `app.scheduling.enabled=false`, so a smoke test scrapes only the source under test; `--scheduling` leaves it
  on. Neither `bootRun` nor the script hot-reloads Kotlin — `down` then `up` after a code change, or the test runs the previous build. Vite does hot-reload.
  Runtime artefacts land in `build/dev-env/` (gitignored). The frontend alone renders but every request 502s; `up all` for the whole stack.
- **Redirect a detached launch** (`> file 2>&1 < /dev/null`): `bootRun` and `vite` inherit the tool's stdout pipe and keep the call hanging after the script exits.
- **Never run Gradle while an import is in flight.** Both Boot modules carry `spring-boot-devtools`, which restarts the service on any task that writes classes
  — `compileKotlin`, a single `--tests` run — and **kills every import mid-flight**. Those sources stay `RUNNING` forever, because the staleness guard only runs
  under the scheduler. The tell is `restartedMain` beside `Started EventsImporterApplicationKt in 1.0 seconds (process running for 117.3)`. Recovery:
  `scripts/dev-env.sh psql "UPDATE events.event_source SET status='IDLE', retry_count=0, version=version+1 WHERE status='RUNNING'"`, then re-trigger.
  On a long job compile first, restart once, then import.
- **A parser fix at a venue whose page has not changed is a 304 forever.** The cached `ETag` / `Last-Modified` skip the import on schedule and manual trigger
  alike. Do not clear the columns; `POST /api/admin/event-sources/<slug>/import?force=true` (#1159) fetches unconditionally and stores fresh validators.
- **Re-keying a live source collides with its own today-dated rows.** A new `sourceId` shape stales every old row, but `removeStaleEvents` spares **today**,
  so a today-dated row keeps its slug while its replacement claims the same one. Re-key on a day the venue is dark, or clear that source's rows first —
  and check which before importing.
- **Do not truncate `<service>.log` while the service runs.** The process keeps its offset, so later writes land behind NUL padding, `grep` calls the file
  binary and `grep -c` prints nothing — which reads as a clean result. Restart to get a clean log; `grep -a` reads a truncated one. **A zero count from a log
  you truncated is not evidence.**
- **In a worktree** ([docs/WORKTREES.md](docs/WORKTREES.md)): files and Gradle output are isolated, the runtime is not. `export COMPOSE_PROJECT_NAME=event-junkie`
  before any `bootRun` or `dev-env.sh up`, or the worktree starts a second empty Postgres and `diff-snapshot` reports every source as `GONE`. Ports `8081` /
  `8080` / `5173` are fixed: `down` in the other checkout first, and the worktree that started the JVM is the code under test. Never import while another
  worktree is importing — `snapshot` counts the whole shared database. Every importer PR conflicts in `docs/EVENT_DATA_SOURCES.md` (recount after rebasing),
  `http/importer/dev-seed.http` (a "keep both" resolution fuses two blocks — rebuild by hand) and the `EventSource.kt` enum. Rebase onto `main`; never merge it in.

## Project skills

Slash commands under `.claude/skills/`, each a one-line `@` pointer into `.github/prompts/`:

- `/code-review` — review the current diff
- `/codebase-audit` — whole-repo review: size, duplication, conventions, simplification
- `/commit-message` — a commit message from the staged changes
- `/compact-comments` — classify each comment block DELETE → RENAME → EXTRACT → RELOCATE → KEEP, apply in that order, measure the drop
- `/data-quality-audit` — read-only audit of the whole `events` database
- `/importer-smoke` — seed, import, inspect the rows and check for regressions, for one importer
- `/k3d-rehearsal` — the chart and all three images on a local k3d cluster, end to end, then torn down
- `/improve-test-coverage` — find and fill coverage gaps
- `/milestone-plan` — a milestone from a list of open issues to an ordered plan
- `/new-issue` — file an issue: duplicate check, the right form, labels, milestone, board fields
- `/next-importer` — one venue from 🔨 Ready in `docs/EVENT_DATA_SOURCES.md` to an open PR; repeat, or run under `/loop`
- `/next-issue` — what to work on next, and why
- `/open-pr` — branch, commit (Conventional Commits), push, open a PR
- `/owasp-top-10` — OWASP Top 10:2025 against the deployed tree, one line per category; `agent-owasp.yml` runs it weekly
- `/plausibility-check` — the next days' events on the public site against the venues' pages; `agent-plausibility.yml` runs it nightly
- `/refactor` — change the shape of the code, not what it does; the acting counterpart to `/codebase-audit`
- `/release-highlights` — the visitor-facing summary that opens a release's notes; `cut-release.yml` runs it before every cut
- `/start-issue <n>` — claim an issue, move the board, cut the branch, read its dependencies, plan
- `/scaffold-importer` — a new venue importer end to end
- `/security-report` — read-only: Dependency-Check findings and Dependabot alerts, reconciled and triaged
- `/security-triage` — work the Security tab to zero: fix, file or dismiss; the mutating counterpart to `/security-report`
- `/squash-commit-message` — a squash commit message for the current branch
- `/update-dependencies` — bump backend and frontend dependencies safely
- `/update-docs` — find documentation that stopped being true; correct, delete or leave it, with the proving check
- `/verify` — the full pre-PR sequence; the prompt is the check list, and it runs every gate the diff touches
- `/write-adr` — the record of a decision already made; claims the next ADR number by writing the file
  `scripts/skill-parity.sh` fails when this list, `.claude/skills/` and `.claude/commands/` disagree; it greps for the bullet shape above.

**Two directory skills are not slash commands**, vendored so every contributor has them and invoked by name: [`asd-ste100`](.claude/skills/asd-ste100/SKILL.md)
(Simplified Technical English; `/compact-comments` and `/update-docs` call it) and [`gh`](.claude/skills/gh/SKILL.md) (from
[`cli/cli`](https://github.com/cli/cli/tree/trunk/skills/gh)). Each has a `VENDORED.md` with its upstream commit and refresh command; **edit nothing else inside
them** — the next update reverts it. [`.github/skills/`](.github/skills) holds one symlink per directory skill, Copilot's documented path; `skill-parity.sh`
asserts each is a symlink onto the right target.

## Automating GitHub with `gh`

The mechanics — `--json`/`--jq`, silent list limits, `-R`, search versus list, when to drop to `gh api` — are the vendored [`gh` skill](.claude/skills/gh/SKILL.md).
What follows is what upstream cannot know: this repository's board, rulesets and bulk edits. The workflow-file half — fork PRs, required checks, what CI may
write to — is in [ci-cd.instructions.md](.github/instructions/ci-cd.instructions.md).

- **A pull request's `mergeable_state` goes stale after a ruleset change, and polling never refreshes it.** GitHub recomputes mergeability lazily, on a PR event
  or a UI view; `gh api …/pulls/<n>` reads the cache. Do not diagnose a stale `blocked` as a live rule — compare the required contexts against what reported:

    ```sh
    gh api repos/OWNER/REPO/rulesets/<id> --jq '.rules[] | select(.type=="required_status_checks") | .parameters.required_status_checks[].context' | sort > /tmp/req
    gh api repos/OWNER/REPO/commits/<sha>/check-runs?per_page=100 --jq '.check_runs[] | select(.conclusion=="success") | .name' | sort -u > /tmp/got
    comm -23 /tmp/req /tmp/got     # empty means nothing required is missing
    ```

    Empty: open the PR in a browser or attempt the merge instead of hunting for a rule that is gone.

- **Pace bulk mutations**: `sleep 0.45` between calls. The _secondary_ rate limit bites long before the hourly one.
- **No `--label` on `gh pr create`.** `label-pr.yml` sets a PR's labels from the title, an added `*Importer.kt` and the `!`/footer, and removes a managed label
  set by hand. On a PR `importer` means one thing, a new venue, and `release.yml` files it under "New Event Sources" (#1546).
- **`gh issue create` takes `--label`; `gh issue edit` takes `--add-label` / `--remove-label`**, and an update must reconcile both directions.
- **Project view grouping and sorting cannot be set through the API** (`ProjectV2ViewConfigurationInput` exposes only `visibleFieldIds`). Manual UI step.
- **gitleaks fires on `key:` with a high-entropy value.** Prefer `slug`, `id` or `name` for identifier fields; widening `.gitleaks.toml` costs coverage.
- **A cautious first run pays for itself**: `--limit 5`, inspect, then continue.

## The Backlog — GitHub Issues

**The backlog is [GitHub Issues](https://github.com/enorm-labs/event-junkie/issues), not a file.** Read a generated snapshot; write through `gh`:

```sh
scripts/generate-backlog-snapshot.sh                # renders every open issue into build/BACKLOG.md — refresh before relying on it
grep -i 'heimathafen' build/BACKLOG.md              # is this already tracked?
gh issue list --label importer --state open         # live state
gh issue view 313                                   # the full body, including its Links footer
```

The snapshot lives in `build/` (gitignored) because the `main` ruleset lets nothing land without a PR and refuses the Actions bot as a bypass actor; its header
carries the timestamp.

**Filing.** `/new-issue` checks for a duplicate and picks the form: 🛠 Task, ✨ Feature, 🔍 Importer / data defect, ⚖️ Decision, 🧭 Epic. The importer-defect
form asks **whether the fix needs a `--full` re-seed** — usually the difference between an hour and a day.

| Finding                                                                                           | Goes to                                                 |
| ------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| A defect with a known repair — we lose or mangle data the source _did_ publish                    | **An issue** (🔍 Importer / data defect)                |
| An accepted limitation — the venue never publishes it, or the parser makes a deliberate trade-off | **That scraper's KDoc**, next to the code it constrains |
| A choice that must be made before work can start                                                  | **An issue** (⚖️ Decision), labelled `needs-decision`   |

**Labels and fields.** Intrinsic properties are **labels** — `area:*`, `size:*`, `importer`, `documentation`. Planning state is Status and Priority on the
[project board](https://github.com/orgs/enorm-labs/projects/1). Issue _type_ is a GitHub issue type, not a label. Three labels say _why_ something cannot
start: `blocked`, `needs-decision`, `needs-deployment` — the last is work that cannot exist yet, not neglect.

**Milestones.** `v0.2 — Deployable` → `v0.3 — Launch-ready` → `v1.0 — Go-live` lead to launch; `Phase 2/3/4` are post-launch buckets. No milestone means
unscheduled. Reasoning in [docs/VISION_ROADMAP_IDEAS.md](docs/VISION_ROADMAP_IDEAS.md).

**Closing.** `Closes #NNN` in the **PR body**, one line per issue, `Closes` not `Fixes`. A commit message would work too under rebase-merge, but the body
survives amending and is one line to fix. Give the PR the issue's milestone; every closed PR here carries one.

## Key Files

The rows here carry a rule or a trap; a plain "where does X live" is one `grep` away and is not listed. ADRs are under `docs/adr/`, named for what they decide;
ADR-032 is the one to know unprompted — no file carries the version, it is computed from the tags and the commits.

| Purpose                                 | Path                                                                                                                                      |
| --------------------------------------- | ----------------------------------------------------------------------------------------------------------------------------------------- |
| Every script, and the `--help` rule     | `scripts/README.md` — gates, tools, and ops; `scripts/index-parity.sh` fails when the directory, the index, or the tree disagrees         |
| Markdown formatting                     | `scripts/format-markdown.sh` + `.oxfmtrc.json` — Markdown only, and the scope is load-bearing                                             |
| README screenshots, and when they rot   | `docs/screenshots/` — dated, because nothing else signals staleness; retake on design changes, never on data changes                      |
| Architecture diagrams, and the gate     | `docs/architecture/` — generated inventories; the diagrams are hand-written in PLATFORM_SETUP.md §1, and ADR-034 says why                 |
| Trivy waivers                           | `.trivyignore` — empty on purpose; an entry needs a reason and a date                                                                     |
| OWASP CVE false-positive suppressions   | `owasp-suppressions.xml`                                                                                                                  |
| Shared MCP servers                      | `.mcp.json` — `opentofu`, the hosted registry lookup; no key, one approval per contributor                                                |
| Infrastructure as code (OpenTofu)       | `infra/` — read `infra/AGENTS.md` first; `bootstrap/` is applied, `environments/` is not                                                  |
| Helm chart (bff · importer · frontend)  | `deploy/charts/event-junkie/` — read `deploy/AGENTS.md` first; exercised on k3d, never on a real cluster                                  |
| Flux resources (one dir per cluster)    | `deploy/clusters/` — read `deploy/AGENTS.md` first; the semver range is on the OCIRepository                                              |
| Backend container images                | `events-bff/Dockerfile`, `events-importer/Dockerfile` — no build-work `RUN`, context is each module's `build/docker`                      |
| Frontend container image                | `events-frontend/Dockerfile` + `events-frontend/docker/nginx.conf` — nginx on 8080, context is the module                                 |
| Chart ↔ image UID                       | `scripts/uid-consistency.sh` — the `USER` line of all three Dockerfiles against what the chart resolves; floor >10000 (#448)              |
| CI: build, scan and publish to GHCR     | `.github/workflows/release.yml` — the only workflow that pushes anything; it does not deploy                                              |
| CI: deployment records from Flux        | `.github/workflows/deployment-status.yml` — the cluster triggers it, not a merge                                                          |
| CI: a failing Flux source, made visible | `.github/workflows/flux-source-failure.yml` — red by construction; the cluster's `source-failure` Alert triggers it (#1454)               |
| CI: blocker issue for a red publish     | `.github/workflows/publish-failure-issue.yml` — one issue per red streak on `main`, closed by the next green publish                      |
| CI: credential expiry reminder          | `.github/workflows/credential-expiry-reminder.yml` — dates live in the workflow, mirrored in docs/CREDENTIALS.md §2                       |
| CI: nightly scan of deployed images     | `.github/workflows/image-scan-scheduled.yml` — a published tag, both arches; thresholds match release.yml                                 |
| CI: DAST, ZAP and Nuclei                | `.github/workflows/dast.yml` — active nightly on an ephemeral k3d, passive and Nuclei weekly on the site; `.zap/`, `.nuclei/`             |
| CI: Lighthouse against production       | `.github/workflows/lighthouse.yml` — four cells after a production deploy and weekly; no trend store (#1698, ADR-033)                     |
| CI: workflow lint + security audit      | `.github/workflows/validate-workflows.yml`; suppressions in `zizmor.yml`                                                                  |
| Agentic workflows                       | `.github/workflows/agent-*.yml` — security opens a PR and dismisses nothing; comments caps twelve files per PR; docs never touches an ADR |
| Release notes categories                | `.github/release.yml`                                                                                                                     |
| Dependabot · Renovate · the boundary    | `.github/dependabot.yml`, `.github/renovate.json5`, ADR-024 — which mechanism owns what; read before adding a fourth                      |
| Releasing & deploying, end to end       | `docs/ops/RELEASING.md` — the diagram; ADR-016 has the reasoning                                                                          |
| Post-deploy checks, where each one runs | ADR-033 — nothing in Actions reaches staging; a hook in-cluster, k3d for a browser suite, production from Actions                         |
| Bootstrapping a cluster, once           | `docs/ops/CLUSTER_BOOTSTRAP.md` — ordered runbook; traps table at the bottom                                                              |
| Connecting to a running cluster         | `docs/ops/CLUSTER_ACCESS.md` — tunnel, kubeconfig, contexts, k9s. Read-only; nothing in it changes anything                               |
| Upgrading k3s on a running node         | `docs/ops/K3S_UPGRADE.md` — in place, not a rebuild; the Traefik check is the one that matters                                            |
| Alerting from outside the cluster       | `docs/ops/HEALTHCHECKS.md` — healthchecks.io dead-man's switches. Ping URLs are credentials and live only on the node                     |
| Secrets, and the SOPS plan              | `docs/ops/SECRETS.md` — three hand-made objects today; the age private key never enters this repository                                   |
| Threat model (STRIDE per boundary)      | `docs/security/THREAT_MODEL.md` — every _mitigated_ row names its file; an _open_ row is an issue. Reread on a new boundary               |
| Platform setup, go-live                 | `docs/ops/PLATFORM_SETUP.md`                                                                                                              |
| Footer, legal pages, versioning         | `docs/LEGAL.md`                                                                                                                           |
| IntelliJ HTTP Client requests           | `http/importer/` (admin) and `http/bff/` (public read) + shared `http/http-client.env.json`                                               |
| Performance tests (k6)                  | `perf/` — `smoke.js` · `load.js` · `spike.js`; `perf/README.md` says what each answers                                                    |
| Stable-sort Pageable resolver           | `events-importer/src/.../StableSortPageableArgumentResolver.kt` — duplicated in `events-bff`                                              |
