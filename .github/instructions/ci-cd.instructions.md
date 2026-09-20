---
applyTo: ".github/workflows/**,.github/actions/**,.github/dependabot.yml,.github/renovate.json5,.github/release.yml,zizmor.yml,.pre-commit-config.yaml"
paths:
    - ".github/workflows/**"
    - ".github/actions/**"
    - ".github/dependabot.yml"
    - ".github/renovate.json5"
    - ".github/release.yml"
    - "zizmor.yml"
    - ".pre-commit-config.yaml"
---

# CI/CD & Automation

What each workflow is for, which checks are required, and the shapes that fail silently. Every workflow's header carries its own decisions in full; this file
is the map and the traps.

## The workflows

**Gates on pull requests**

- `build-backend.yml` — `ktlintCheck`, the three detekt analyses (nine SARIF reports in **one** upload, kept apart by `runAutomationDetails.id`, #1394), build,
  test, Kover to the summary and a sticky PR comment, an informational OWASP scan. Sets `ORG_GRADLE_PROJECT_warningsAsErrors=true`, so a Kotlin warning fails
  here and nowhere else. Also builds both container images for `linux/amd64` and `linux/arm64` with `type=cacheonly` and **never pushes** — it runs on fork
  PRs. `build-frontend.yml` — `npm ci`, lint, build, unit, Playwright e2e, the frontend image from its own `dist/`. Both build images on PRs only, since
  `release.yml` builds them on every push to `main`. Both declare `workflow_dispatch` (`gh workflow run build-backend.yml --ref <branch>`), which ignores
  the path filters — and only exists for workflows on the default branch, so one added in a PR is unusable until it merges.
- `codeql.yml` — advanced setup, not default setup, because default setup produces no run on a fork PR and the required context sat Pending forever (#581).
  **Never move it back.** The job names are the required contexts; a new step in `analyze` needs `if: steps.relevance.outputs.value == 'true'` or it runs
  on a docs-only PR with nothing checked out.
- `dependency-review.yml` — newly introduced vulnerabilities (high+) and licence issues against the Advisory Database. `dependency-submission.yml` — the
  Gradle graph to GitHub on `main` push.
- `validate-chart.yml` — `helm lint --strict`, `helm template | flux schema validate` over every values file and cluster, `helm unittest`,
  `scripts/cluster-assertions.sh`, `scripts/uid-consistency.sh`. `HELM_VERSION` tracks the SDK helm-controller embeds (Helm 4 since v1.6.x) — a constraint,
  not a lag; it was held at 3.x on a lapsed premise until #1006, and Helm 3's `--strict` misses what 4's rejects. Reaches no cluster.
- `validate-infra.yml` — `tofu fmt -check`, `init -backend=false` + `validate` per stack in a matrix, ShellCheck on cloud-init, `check_user_data.py`. **Never
  `plan`** — nothing outside the cluster holds a credential (PLATFORM_SETUP.md §4), so no drift detection.
- `validate-workflows.yml` — actionlint and zizmor at `--min-severity medium`; suppressions in `zizmor.yml` or inline `# zizmor: ignore[…]`, each with a reason
  and a date. `unpinned-uses: hash-pin` since #443.
- `validate-docs.yml` — `scripts/format-markdown.sh check` over every `.md`, plus `skill-parity.sh` and `rules-parity.sh`. Checks, never writes (a push-back
  would need write access on fork PRs). Installs the frontend's lockfile for the **pinned** oxfmt; `package-lock.json` is in its filter because an oxfmt bump
  reformats every document.
- `validate-notices.yml` — `scripts/notices-parity.sh check`. Its own workflow because regenerating needs a JDK and Node in one job. **Required, so no
  `pull_request` filter**; runs on every PR at ~1 min warm, 3.5–4.5 cold, priced in #1084 because the next workflow removes the reason it would be red on
  bot branches. Works only because the generator writes no timestamp (#1037).
- `fix-notices-on-bot-prs.yml` — regenerates `notices.json` on Dependabot and Renovate branches and pushes, since neither bot can. Four decisions in the
  file: `workflow_run`, not `push` (a Dependabot push gets a read-only token and no secrets); not `pull_request_target` (a writable token plus `npm ci` on
  the branch's lockfile is arbitrary code execution); the App token is minted **below** `npm ci`, with `contents: write` only; the commit author is not
  `renovate[bot]`, so both bots abandon the branch rather than force-push the fix away — at the cost that the bump stops auto-rebasing.
- `validate-python.yml` — `ruff check` + `ruff format --check` at `RUFF_VERSION` from the pinned image, then the two Python tests (#1189). `validate-scripts.yml`
  — ShellCheck the same way, plus the script test suites (`version-test.sh`, `version-deserved-test.sh`, `release-highlights-test.sh`). `validate-comments.yml`
  — `scripts/comment-lint.sh check`.
- `label-pr.yml` — type labels from the Conventional Commits title (`fix(api)!:` → `fix` + `breaking-change`), `importer` from an added `*Importer.kt` under
  `scraper/`. **Required, and red on a `feat` outside a product scope** (`frontend`, `events`, `promoters`, `venues`, `artists`, `importer`, `scraper`,
  `bff`, `images`, `branding`) — a `feat` earns a minor, and `feat(ci)` once did (v0.17.0); `scripts/scope-parity.sh` holds every copy of that list.
  `pull_request_target`, no checkout, `github-script`. `milestone-dependabot.yml` — same shape; gives every bot PR (Dependabot, Renovate, the release App
  should it open one again) the **oldest open milestone**, never overwrites one, and its dispatch sweeps the backlog. Both match on the bot's login, so a new
  bot joins `BOTS` or arrives without a milestone.
- `merge-gate.yml` (#1424) — required; fails when the author is a Bot outside `dependabot[bot]`, `renovate[bot]`, `event-junkie-release[bot]` **unless a
  User has approved the current head**. A push after approval turns it red again; the `claude` App's approval does not count. Runs on `pull_request_target`
  and `pull_request_review` **on purpose**: the file executes as it stands on `main`, so an App with `workflows: write` cannot edit it green from the PR it
  gates. It checks out nothing.

**Publishing and what follows it**

- `release.yml` — **the only workflow that publishes anything.** Four images and the chart from one computed version, Trivy before push, a snapshot on every
  push to `main`, a release on a `v*` tag. It does not deploy; Flux pulls. Deliberate and easy to "fix" wrongly: **no path filters** (the chart's
  `appVersion` names all image tags, so a chart without all images is broken), **no tests** (the PR gates), **two builds per image** (a multi-platform image
  cannot be loaded for scanning before it is pushed), **publishes on an allowlist** (`push`, or a dispatch with `publish` ticked — never "everything but the
  dry run"), and **tests itself on PRs that change it**, because the dispatch button does not exist until the merge that publishes. Uploads the Trivy tables
  as a `trivy-reports` artifact for `publish-failure-issue.yml`.
- `cut-release.yml` (#868) — publishes the GitHub Release that `release.yml` keys on; `workflow_dispatch` only, `dry_run` default. **Refuses a commit whose
  snapshot publish is not green** (v0.3.10 left an empty tag, #1117). **The version is never typed and never chosen**: no file carries it (ADR-032), and
  `scripts/version.sh deserved` reads the commits since the last release tag — a `feat` in a product scope is a minor, a break a major (a minor before
  1.0.0), the rest a patch (RELEASING.md § What a release deserves). `release.yml` computes the same number from the same commits and refuses a tag that
  claims another. Only input: `at_least`, for `1.0.0`. `scripts/version-deserved-test.sh` asserts the rule and what `compute` names a commit. Mints an App
  token because a release created with `GITHUB_TOKEN` fires no `release: published`, narrowed with `permission-contents`, which zizmor's `github-app`
  audit enforces. **Opens no pull request**: the next snapshot is named after the next number by the same script, so staging follows `main` the moment
  something merges.
- `publish-failure-issue.yml` (#1122) — one issue per red streak of `release.yml` on `main`, closed by the next green publish; `workflow_run`, no checkout, a
  job guard admitting only a push to `main` or a `release` event. Quotes the Trivy tables when the artifact exists. Its dispatch replays a `run_id`.
- `image-scan-scheduled.yml` — nightly Trivy on the **deployed** images, which the publish gate cannot do: a CVE disclosed against a running image triggers
  no build. Scans a published tag `scripts/deployed-versions.sh` resolves as Flux does (paginated — #1027 scanned a fortnight-old snapshot for weeks), both
  arches, thresholds identical to `release.yml`'s so a finding here means a new advisory. **The fix for a red run is a release**, not a re-run.
- `dependency-check-scheduled.yml` — the authoritative nightly OWASP scan; owns the NVD cache the PR scan restores.
- `dast.yml` (#1421, #1423, #1461) — the only scanner that sends requests; three jobs, no cluster credential. `dast-k3d` nightly: the newest signed snapshot on an
  ephemeral k3d via `k3d-rehearsal.sh flux-up`, `.zap/seed.sql`, the full scan through Traefik, the API scan past the limiter on purpose, Nuclei's `misconfig`,
  `exposure`, `tech`, `cve` templates. `dast-production` weekly passive and `nuclei-production` weekly at a fifth of the limiter's rate, against `SITE_URL`.
  Red only on a `FAIL` rule in `.zap/rules-*.tsv` or a Nuclei match outside `.nuclei/waivers-<target>.tsv`; every `IGNORE` and waiver dated and reasoned.
  Reports are artifacts; nothing opens an issue; no `pull_request` trigger, decided.
- `deployment-status.yml` (#565) — turns Flux's `repository_dispatch` (`HelmRelease/event-junkie.flux-system`) into a GitHub deployment. Parses the commit
  out of the chart version through `scripts/version.sh`'s two shapes — change one, change this — after stripping helm-controller's `+<digest>` build metadata.
  `flux-source-failure.yml` (#1454) — the other listener, on `OCIRepository/event-junkie.flux-system` from each cluster's `source-failure` Alert; a failed
  signature or an unreachable registry keeps the last artifact and emits no HelmRelease event, so this job is **red by construction** — the one shape GitHub
  mails about. **Neither can be tested from a PR**: `repository_dispatch` runs the default branch only, so both fail loudly on an unrecognised payload.
- `site-probe.yml` — the daily outer half of #271's alerting, against `SITE_URL` with the apex fallback (ADR-021 says why Better Stack is the other half).
  `mail-probe.yml` (#637) — proves `hello@` and `security@` still receive, because a dead mailbox looks exactly like a quiet week.

**Reminders — none can place a board card** (`GITHUB_TOKEN` cannot write to an org project, and Auto-add is unreliable, #1092): check after a run and use
`scripts/issue-board.sh status <n> Ready`.

- `restore-drill-reminder.yml` — quarterly, and on any push to `main` touching `backups.sh` or `postgres.sh`; idempotent by listing open issues, not search.
- `credential-expiry-reminder.yml` (#569) — 30 days before a credential expires, louder once it has. **The dates are a literal `CREDENTIALS` table in the
  workflow, duplicated in `docs/CREDENTIALS.md` §2**; reading them from GitHub would need `admin:org`, a stronger expiring token watching a weaker one.
- `node-pin-reminder.yml` (#1068) — when `k3s_version` or `walg_version` falls behind upstream. **Refuses to open a PR, load-bearing**: both pins feed
  `user_data`, so a bump replaces the node, and `walg_checksums` must move with the version or `backups.sh` aborts the boot. `scripts/upstream-node-pins.sh`
  holds the comparison (exit 0 current, 1 behind, 2 could not check — only 2 fails the job); titles carry the pinned version so upstream moving cannot pile
  up issues. What to do with the issue is `docs/ops/K3S_UPGRADE.md`, and it is not a rebuild.

**Agent workloads** (#387) — six workflows, each driving one of this repository's prompts through `claude-code-action`, each opening a PR or a report and
never pushing to `main`. Decisions shared by all six:

- **`--unattended` is a clause in each prompt**, not a hint: the "ask first" tier has nobody to ask, so unattended dismisses, files and rewrites nothing it
  is not sure of. **`--allowedTools` is load-bearing** — the prompts carry no frontmatter, so without it the agent has no shell and reads the tree and does
  nothing. **`github_token` is never passed**, so the action authenticates as the Claude App: a PR pushed on `GITHUB_TOKEN` starts no check run and sits
  Pending forever. **`ACTIONS_GITHUB_TOKEN`** carries `${{ github.token }}` under a name the action leaves alone, because the action overwrites `GITHUB_TOKEN`
  and `GH_TOKEN` with the App token, which cannot read code scanning — every run before #1021 inventoried nothing and reported clean. The two names must
  agree, and nothing fails loudly if they stop. Dependabot alerts `403` for both tokens.
- **A schedule cannot pass inputs**: on a cron `inputs.x` is empty, so every input reads `inputs.x || '<default>'` and the dry-run flag is additionally gated
  on `github.event_name == 'workflow_dispatch'` — "scheduled runs are live" is written down, not inherited. Scheduled workflows run from the default branch
  only, GitHub disables the schedule after 60 days without activity, and the run is attributed to whoever last edited the `cron` line, which must be a human.
  Five run nightly, staggered (security 04:23, refactor 04:41, plausibility 05:17, comments 06:14, docs 06:35 UTC), OWASP Monday 06:31; `concurrency` stops
  a dispatch racing its cron.
- **None can be tested from a branch** — upstream: the App token is issued only when the workflow file is byte-identical to `main`'s, and the step then ends
  `outcome=success` having run nothing. `Fail if the report is a stub` (under 400 bytes or five lines) is the guard, and a run with no report fails the job.
  Verify a change **after** merge, by dispatching on `main` with `dry_run: true`.
- **`display_report: true`, never `show_full_output`** — the latter dumps every tool result, which the action warns may hold secrets. A step summary has no
  API, so each workflow extracts the final report from `execution_file` into an `agent-report` artifact; only the report, never the file. **Every count in a
  report carries the command that produced it** — two runs minutes apart disagreed about a pattern's existence, and the zero without evidence was wrong.
- **The Claude App holds `workflows: write` repository-wide** (#996), granted for a pin sweep Renovate now does (#1071); nothing needs it, and review of any
  agent PR touching `.github/workflows/` is the actual control.

The six: `agent-security.yml` (`/security-triage`; nightly and on a red publish via `workflow_run`, walking the prompt's § A blocked publish with
`--failed-publish`; code scanning only), `agent-refactor.yml` (`/refactor`; fenced away from `SlugGenerator`, `GenreNormalizer`, `ArtistNameMapping`,
`MoneyExtensions`, where a change passes every test and still changes the rows — reported, never applied), `agent-comments.yml` (`/compact-comments`; DELETE,
RENAME, EXTRACT only, RELOCATE and KEEP reported, venue KDoc off-limits, `--all` not `--worst N` because density ranking selects the files that are dense on
purpose, twelve files per PR; default model `claude-opus-4-8`, a measured verbosity preference), `agent-docs.yml` (`/update-docs`; corrects facts, rewrites no
argument, `docs/adr/` off-limits — a stale ADR gets a Status line, not a rewrite; installs the pinned oxfmt), `agent-plausibility.yml` (`/plausibility-check`;
no `dry_run` because every run is one, no `issues: write` on the agent's job, ADR-007's politeness rules on a runner) and `agent-owasp.yml` (`/owasp-top-10`,
#1422; weekly, diff-first). The last two mail their report by commenting on one issue per month through `.github/actions/report-to-issue`, from a separate
`notify` job that alone holds `issues: write` (#1499); nothing becomes an issue until a person files it.

## Rules every workflow follows

- **Every `uses:` names a commit SHA with the version in a trailing comment** (#443): `uses: actions/checkout@3d3c42e5… # v7.0.1`. Dependabot reads the
  comment and rewrites both. `Lint & audit workflows` refuses a tag; the repository setting `sha_pinning_required` refuses the run — read it from
  `repos/{owner}/{repo}/actions/permissions`, because the repository object answers `null` whatever the value. **Every `actions/checkout` sets
  `persist-credentials: false`**; nothing here pushes with git, and zizmor's `artipacked` cannot read a version out of a SHA.
- **Steps that verify come first; steps that report to GitHub come last** (#507). `if:` carries an implicit `success()`, so a failed step skips everything
  below it, and a skipped step leaves no annotation. Anything that calls the API (`upload-sarif`, a comment, `github-script`) goes after everything that
  builds, tests or scans, with `success() || failure()`. It bit twice during one GitHub incident: a coverage comment before the image build dropped the
  build on the PR that changed the base image (#506); a SARIF upload before the publish left staging without a chart.
- **Every scanner gate asserts a denominator as well as an exit code** (#1087) — `scripts/scan-coverage.sh` against `scripts/scan-coverage-baseline.txt`, the
  one place output formats are parsed, so an extraction that stops matching is an error. Three shapes: a floor that only moves up (zizmor, `flux schema
validate`), a property (every rendered resource valid, none skipped, count positive), a floor of zero where an upstream database moves the count (Trivy,
  Dependency-Check — the tool `Dependencies Scanned: 0` happened to).
- **Every step that writes to GitHub is guarded on `github.event.pull_request.head.repo.fork != true`** — coverage comments, SARIF uploads — or a fork PR goes
  red for a `403` its author did not cause. Add the guard in the same change as the step.

## The `main` ruleset

- **Required checks are a repository setting; this file does not name their number** (it drifted twice). Read `gh api repos/{owner}/{repo}/rulesets` — not
  `branches/main/protection`, which answers `404 Branch not protected` because enforcement is a ruleset, and that `404` reads as "nothing enforced".
- **Each required check runs on every PR**, because GitHub keeps a required-but-skipped check Pending forever (#447 never ran `Lint & render`). So: **never add a
  `paths:` filter to the `pull_request` trigger of a required workflow** (the failure looks like a hung check), and **adding a stack to `validate-infra`'s
  matrix creates a context that is not required** — add it to the ruleset in the same change.
- **`Build & Test (backend)` and `(frontend)` are required, and neither is the job that does the work.** Each workflow is `detect-changes` (seconds, no
  checkout, the PR's file list from the API), the build gated on it, and a **`gate` job that always runs and carries the context** — red when the build
  failed, green when skipped, red when detection failed (a detection bug must never read as a green build). A job skipped by a workflow-level `paths:`
  creates no check run at all, so the required context must never be the conditional job. The gate declares `contents: read` only, which is what keeps the
  fork path working while the build holds write scopes for its comment and SARIF. `codeql.yml`'s three contexts do the same with a `relevance` step. OWASP is
  deliberately outside the gate's `needs`: `NVD_API_KEY` is empty on a fork.
- **No bypass actor, nothing in CI pushes to `main`, and no unlisted App lands a PR.** #443 removed the admin bypass. GitHub refuses the Actions bot as a
  bypass actor, and a second ruleset restricting updates broke auto-merge (GitHub's deferred merge runs without bypass, known and not planned) — it is gone.
  **Design any workflow that wants to write as generate-on-demand or open-a-PR, never push-to-main or merge**; a snapshot workflow was written, merged and
  deleted before this was learned. What the gate does not stop: an App with `contents: write` pushing onto a person's open branch with auto-merge armed —
  an accepted row in the threat model.
- **Fork pull requests work because every required check declares `contents: read` and depends on no secret** (#479, #579). Adding a secret or a `write`
  permission a step depends on breaks the fork path invisibly, since nothing exercises it routinely. `NVD_API_KEY` is referenced on the PR path and is empty
  on a fork; the invariant is "no PR _depends_ on a secret". **`pull_request_target` is safe only because the workflow checks out and runs nothing** —
  `label-pr.yml`, `milestone-dependabot.yml`, `merge-gate.yml` each open with that banner; one innocuous `actions/checkout` makes it arbitrary code execution
  with write scopes.
- **Secret scanning, push protection and validity checks are on; `non_provider_patterns` and `ai_detection` are off, decided** (#443, #385, #1427). Read them:
  `gh api repos/{owner}/{repo} --jq '.security_and_analysis | map_values(.status)'`. gitleaks in pre-commit is the local half.
- **Commits are deliberately not signed** (#443): with one maintainer a signature proves authorship to the person who holds the only merging account, at the
  cost of a key on every machine and every agent. The condition that changes this is a second committer.
- **Releases are immutable** (#443), and publishing one triggers a production publish (#264) — a release published by mistake is fixed by shipping forward,
  never by reusing the tag. `repos/{owner}/{repo}/immutable-releases`.
- **Every published image carries a buildx SBOM (`sbom: true`), a signed provenance attestation, and a keyless cosign signature on the digest** (#443,
  #1425); the chart carries the signature too. Provenance says where it came from (`gh attestation verify oci://… --repo enorm-labs/event-junkie`, quiet on
  success); the SBOM says what is inside; the cosign signature is the one a cluster enforces (`spec.verify`, identity `…/release.yml@<ref>`), and a signing
  failure leaves a verifying cluster on its last artifact until a re-run signs the same digest.
- **When CI misbehaves, check [githubstatus.com](https://www.githubstatus.com/api/v2/summary.json) first.** Seen in the 2026-08-06 outage: no run created at
  all (webhooks throttled — the PR gets no label either); a run "failing" with zero steps, annotated `The job was not acquired by Runner of type hosted` —
  runner starvation, not a test failure; runs for branches deleted hours ago; the Webhooks component reading Operational throughout; `gh run list` empty
  while CodeQL still reports; and `head_sha` on `/actions/runs` silently returning `total_count: 0` for an abbreviated SHA. With CI down, `/verify` locally and
  say in the PR that CI never ran.

## Dependency updates

Three mechanisms, and they must not overlap (#384, ADR-024): **Dependabot** owns the six ecosystems declared in `dependabot.yml`; **Renovate** owns what
belongs to no ecosystem — Flux and the charts it installs, images in plain manifests, the CI tool pins in `.github/workflows/`, `.pre-commit-config.yaml`, the
Gradle wrapper; **`/update-dependencies`** is the deliberate sweep that knows which Gradle versions are BOM-managed and must not be pinned.

- **Dependabot** runs weekly, everything grouped. `gradle` by library family; `npm` with `versioning-strategy: increase` (keeps the exact pins) and five
  toolchain families plus a `frontend-minor-patch` sweep that **must carry `patterns: ["*"]`** — a dependency joins the most _specific_ matching group, and a
  group with no `patterns:` outranks a wildcard, which is what split the oxlint pair twice (#494); `github-actions` in one group, with
  `.github/actions/report-to-issue` as a second directory or its pins rot unseen; `opentofu` (**not `terraform`** — separate registries, and the `terraform`
  updater rewrites the lock file's `registry.opentofu.org`), all four directories in one PR, moving `.terraform.lock.hcl` and not `versions.tf`; `docker` for
  the tag-and-digest base-image pins — **an open Dependabot PR is a live vulnerability**, and when bumping a base image check the branch is still being
  rebuilt (`nginx 1.29` shipped three-month-old Alpine packages); `docker-compose`, which decides what Testcontainers exercises.
- **A tool version pinned as a plain string belongs to no ecosystem** — `HELM_VERSION`, `FLUX_VERSION`, `FLUX_SCHEMA_VERSION`, `HELM_UNITTEST_VERSION`,
  `TRIVY_VERSION`, gitleaks' `rev:`. Renovate's `customManagers` watch them by `depName`, with no `# renovate:` comment beside a pin, so one PR moves a pin
  across every file that carries it. The two `TRIVY_VERSION` pins must agree or the scheduled scan stops being comparable with the publish gate.
- **Renovate's `enabledManagers` is an allow-list, and that is the whole safety argument**: a manager absent from it cannot open a PR. `custom.regex` is
  doubly gated — disabled wholesale by a `packageRules` entry and re-enabled per `depName`; a pin in `customManagers` and not in that list shows as
  `SKIPPED: disabled` in a dry run. `flux`, `kubernetes` and `pre-commit` do nothing on their defaults. `managerFilePatterns` is additive, not a replacement —
  exclude with `packageRules` + `matchFileNames`. `labels: ["dependencies"]` is load-bearing (release notes sort by label). The `chore(deps)` prefix comes from
  `config:recommended` and is not configured; **never key anything on the prefix**, the label is the stable signal.

## Skills, commits, releases

- **A skill is three files** — `.github/prompts/<name>.prompt.md`, one-line `@` pointers in `.claude/skills/` and `.claude/commands/`, a bullet in `AGENTS.md`
  § Project skills — and `scripts/skill-parity.sh` keeps them in step, because nothing else does.
- **Conventional Commits** 1.0.0. `/open-pr` is the explicit go-ahead for the commit and push the "no unsolicited commits" rule withholds.
- **Release notes** (`.github/release.yml`) group by the labels `label-pr.yml` applies, first category wins, so specific (`importer`, `dependencies`) precede
  general; `ignore-for-release` hides a PR; the release App's own PRs are excluded by author, kept from before ADR-032 when it opened a bump every cycle.
  **A visitor-facing summary sits above the categories**:
  `cut-release.yml` runs `/release-highlights`; three sources in order — the `highlights` input, the model, `scripts/release-highlights.sh` — and the run
  summary names which. The model step is `continue-on-error` and reports success without running when the file differs from `main`'s, so the next step checks
  the file, never the outcome.
