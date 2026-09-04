---
applyTo: ".github/workflows/**,.github/dependabot.yml,.github/renovate.json5,.github/release.yml,zizmor.yml,.pre-commit-config.yaml"
paths:
    - ".github/workflows/**"
    - ".github/dependabot.yml"
    - ".github/renovate.json5"
    - ".github/release.yml"
    - "zizmor.yml"
    - ".pre-commit-config.yaml"
---

# CI/CD & Automation

What each workflow is for, which checks are required, and the shapes that fail silently.

- **GitHub Actions** runs these workflows (`.github/workflows/`):
    - `build-backend.yml` — Lint (`ktlintCheck`), static analysis (`detekt`), build, test, and OWASP dependency CVE scan. Posts detekt markdown reports and
      Kover coverage to the job summary; on PRs, also posts Kover coverage as a sticky comment (via `mi-kas/kover-report`). Detekt SARIF reports are uploaded
      per module to GitHub Code Scanning. Triggers on `main` push/PR, skips
      `events-frontend/**`, `*.md`, `docs/**`. Its build job also sets `ORG_GRADLE_PROJECT_warningsAsErrors=true`, so a Kotlin warning fails the build here and
      nowhere else — see [kotlin.instructions.md](kotlin.instructions.md).
    - `build-frontend.yml` — Install, lint, build, unit test, and Playwright e2e test. Triggers only when `events-frontend/**` changes. Uses Node 24.
    - Both build workflows also declare **`workflow_dispatch`**, so they can be run by hand —
      `gh workflow run build-backend.yml --ref <branch>` (or the Actions tab). This exists because the automatic triggers cannot always be relied on: during the
      2026-08-06 Actions outage GitHub throttled webhooks to ~15%, so four PRs merged without a run ever being _created_, and `gh run rerun` cannot help when
      there is no run to re-run. A manual run ignores the path filters, so it also answers "build this ref anyway". **Caveat:** GitHub only offers a manual
      trigger for workflows present on the **default branch**, so a `workflow_dispatch` added in a PR is not usable until that PR merges.
    - `dependency-review.yml` — Runs on PRs to diff dependency changes between base and head. Flags newly introduced vulnerabilities (high+ severity) and
      license issues using the GitHub Advisory Database. Complements OWASP Dependency-Check with fast, PR-scoped feedback.
    - `dependency-submission.yml` — Submits Gradle dependency graph to GitHub on `main` push (for Dependabot alerts/security).
    - `dependency-check-scheduled.yml` — The authoritative nightly OWASP Dependency-Check on `main`. Owns the shared NVD cache that the informational PR scan in
      `build-backend.yml` restores.
    - `image-scan-scheduled.yml` — The nightly Trivy scan of the images that are **deployed**, which is the half `release.yml`'s publish-time gate structurally
      cannot do: a CVE disclosed against an already-running image triggers no build, so that gate is silent exactly when the risk is newest. Same split as the
      two Dependency-Check workflows above. Three things about it are decisions rather than defaults. **It scans a published tag, not a rebuild of `main`** —
      `scripts/deployed-versions.sh` reproduces Flux's own selection, so the target cannot drift from what is deployed and production starts being scanned by
      itself the day it has a release — a claim that held only after #1027, because the resolver read one 100-tag page of a paginated registry and every release
      chart sat past it, so this job scanned a fortnight-old snapshot and called production unscannable. **It scans arm64 as well as amd64**, which `release.yml` cannot (a multi-platform image cannot be loaded into a local
      daemon before it is pushed) and which matters because arm64 is what the Hetzner nodes run. **Its thresholds match `release.yml`'s exactly**
      (`CRITICAL,HIGH`, `--ignore-unfixed`) so that a finding here which the publish gate did not raise means the advisory is new rather than the scanner
      different. It asserts a non-zero package count per image, because a scan that enumerates nothing reports as clean — the `Dependencies Scanned: 0` lesson,
      one surface over. **The fix for a red run is to cut a release**, not to re-run the job: these images are immutable and already deployed.
    - `restore-drill-reminder.yml` — Opens the quarterly PostgreSQL restore drill as an assigned issue, and again on any push to `main` touching `backups.sh` or
      `postgres.sh`. Documented as quarterly is not a schedule; this is what makes a skipped quarter visible as an open card rather than as nothing at all.
      Idempotent by listing open issues rather than searching (the search index is eventually consistent). It does not put the issue on the board itself
      (`GITHUB_TOKEN` cannot write to an organisation project); the board's `Auto-add to project` workflow was enabled on 2026-08-18 to cover that, and the
      next issue it opens is what confirms it. See `docs/ops/BACKUPS.md` §9.
    - `label-pr.yml` — Derives labels from the Conventional Commits PR title (`feat(scraper): …` → `feat` + `importer`, `fix(api)!: …` → `fix` +
      `breaking-change`) via `actions/github-script`. Creates any missing label on demand and re-syncs when the title is edited. Uses `pull_request_target` so
      fork PRs get a writable token; safe because it never checks out or runs PR code.
    - `milestone-dependabot.yml` — gives every Dependabot pull request a milestone, since `dependabot.yml` has no key for one and they are otherwise the
      single class of pull request that arrives without one. Same shape and same banner as `label-pr.yml`: `pull_request_target`, no checkout, `github-script`.
      It picks the **oldest open milestone** — no milestone here carries a due date, so there is no string to keep current, and when one closes the next wins by
      itself. It never overwrites a milestone already set. Its `workflow_dispatch` sweeps every open Dependabot pull request that has none, which is what covers
      the ones predating it.
    - `deployment-status.yml` — turns a Flux `repository_dispatch` into a **GitHub deployment**, so the Environments tab says what is running (#565). Triggered
      by the `github-dispatch` Provider in each cluster, on the event type `HelmRelease/event-junkie.flux-system` — Flux's own `{Kind}/{Name}.{Namespace}`
      format, not a name we chose. **It is the only workflow that cannot be tested from a pull request**, because `repository_dispatch` runs workflows from the
      default branch only; it therefore fails loudly on any payload it does not recognise rather than defaulting. The revision Flux reports is a _chart
      version_, not a commit, so it parses the commit back out of `scripts/version.sh`'s two shapes — change one and this must change with it. Note that
      helm-controller appends the chart's OCI digest as SemVer build metadata (`…g3b1c09e+97ec754320b5`), which is stripped before matching.
    - `credential-expiry-reminder.yml` — opens an assigned issue 30 days before a credential expires, and a louder, differently-titled one if the date passes
      anyway (#569). Weekly. **The dates are a literal `CREDENTIALS` table in the workflow, duplicated in `docs/CREDENTIALS.md` §2, and the two must move
      together** — reading them from GitHub instead would need `admin:org`, which `GITHUB_TOKEN` cannot hold, so that route would watch an expiring token with
      a stronger expiring token. Only `github-dispatch` has a date today (2027-08-20); nothing else here expires.
    - `agent-security.yml` — **the only workflow that runs an agent**, and the first of #387's four workloads. Claude, driven by this repository's own
      [`/security-triage`](../prompts/security-triage.prompt.md) prompt, opening a pull request and never pushing to `main`. Five things about it are decisions.
      **It invokes the prompt as `--unattended`**, which is a clause in the prompt rather than a hint: the "ask first" tier has nobody to ask from a runner, so
      unattended it dismisses nothing and files nothing, and the candidates go into the pull request body. **It never passes `github_token`**, so the action
      authenticates as the Claude GitHub App — Actions does not trigger workflows on `GITHUB_TOKEN` commits, and a pull request whose pushes start no run sits
      Pending against every required check forever. **`--allowedTools` is load-bearing**: these prompts carry no frontmatter, so without it the agent has no
      shell and no GitHub API and the run reads the repository and does nothing. And **Dependabot alerts are expected to `403`** — neither `GITHUB_TOKEN` nor the
      Claude App carries a permission for them — so its honest scope is code scanning. It runs on the nightly schedule below, and `dry_run` defaults to true only
      on a manual dispatch — a scheduled run is live.

        The fifth decision is **`ACTIONS_GITHUB_TOKEN`**, and it is the one that makes the fourth true. `security-events: read` is granted to the Actions token,
        but the action overwrites `GITHUB_TOKEN` and `GH_TOKEN` in the environment the agent inherits with its App installation token — so `gh` authenticated as
        the App and code scanning answered `403` as well, and every scheduled run from the workload's creation to #1021 inventoried neither surface. The workflow
        now passes `${{ github.token }}` as `ACTIONS_GITHUB_TOKEN`, a name the action leaves alone, and Step 1 of the prompt reads code scanning through it.
        **The two names must agree**, and nothing fails loudly if they stop: a blind inventory and a clean one both return an empty list. Requesting
        `security_events` on the App token was the alternative and is not available — the action documents `actions`, `checks`, `discussions` and `workflows` as
        the values `additional_permissions` accepts, and no more.

    - **The Claude App carries `workflows: write`, and every agent workload has it** (#996). Granted because `agent-dependencies.yml` exists to sweep tool
      pins and eight of its ten live in `.github/workflows/` — so the one job that workload has was the one its credential forbade, and every run that found
      something failed at the push after doing all the work. **`GITHUB_TOKEN` is not an alternative**, for the reason `agent-security.yml` already records: a
      pull request it pushes starts no check run and sits Pending against every required check forever. A scoped token minted per run was the narrower option
      and was not taken, so the grant is repository-wide. **A workflow file is the one file where a bad edit changes what CI itself may do**, which makes review
      of any agent pull request touching that directory the actual control. Nothing else changed: the agents still open pull requests and still never push to
      `main`.
    - **No agent workflow can be tested from a branch, and this is upstream, not a repository choice.** The action exchanges its OIDC token for the App
      token only if the workflow file is byte-identical to the copy on the default branch, so a `workflow_dispatch` on a branch that edits one gets
      `Workflow validation failed. The workflow file must exist and have identical content to the version on the repository's default branch.` **The step
      then ends `outcome=success`** — the action treats the skip as a clean exit, so a job without a further guard goes green having run no agent at all.
      That is what the `Fail if the report is a stub` step in each workload is for, and it caught this on the first attempt (run
      [33752364761](https://github.com/enorm-labs/event-junkie/actions/runs/33752364761)). So a change to one of these five files is verified **after** it
      merges, by dispatching it on `main` with `dry_run: true` — which writes the report to the job summary and opens nothing. Plan the change knowing its
      proof comes last.
    - `agent-docs.yml` — the `/update-docs` workload, and **the one #387 puts last on purpose**: a wrong answer is a plausible-looking paragraph nobody
      notices for months. `--unattended` limits it to detecting and **correcting facts** — a path that does not resolve, a command that fails, a number that
      disagrees with its named source of truth, an issue whose state is wrong. It rewrites no argument, simplifies nothing and deletes no paragraph; those are
      reported. `docs/adr/` is off-limits by construction, because an ADR describing something no longer true is a decision that was later changed, and the fix
      is a **Status** line rather than a rewrite of the argument that would destroy the only record of why the old choice was made. `BRANDING.md` and
      `LOGO_IDEAS.md` are exempt as voice-carrying copy, and `ACCEPTED_LIMITATIONS.md` is generated. It installs Node and the frontend's lockfile, because
      `format-markdown.sh` needs the **pinned** oxfmt rather than one on `PATH` — the same reason `validate-docs.yml` does it.
    - **All five run nightly, staggered across one overnight window, and a scheduled run is live.** Security 04:23, refactor 04:41, dependency pins 05:52,
      comments 06:14, documentation 06:35 UTC, each off-the-hour like every other schedule here. They were one per weekday until the cadence moved: nightly buys
      a finding on the day it appears rather than up to a week later, and costs up to five open agent pull requests a day rather than five a week. The
      staggered start times are what remains of the weekday spread, and the `concurrency` group on each stops a manual dispatch racing its own cron and stops a
      long run being lapped by the next night's. **A schedule cannot pass inputs, and this is the trap**: the `inputs` context is
      populated only for `workflow_dispatch` and `workflow_call`, so on a cron `inputs.model` is the empty string and `--model` reaches the CLI with no value,
      while `inputs.dry_run` is falsy and the run goes live by accident rather than by decision. Every input is therefore read as `inputs.x || '<default>'`, and
      the dry-run flag is additionally gated on `github.event_name == 'workflow_dispatch'` so that "scheduled runs open pull requests" is written down rather
      than inherited from an empty context. `inputs.scope` matters as much as the other two: empty means "the current diff", and a scheduled run on `main` has
      none, so the refactor job would rank nothing and the comment job would sweep nothing. Two further GitHub properties apply and neither is ours to
      configure — scheduled workflows run **from the default branch only**, and on a public repository GitHub **disables the schedule after 60 days without
      repository activity**. The action also rejects a bot actor unless it is named in `allowed_bots`, and a scheduled run is attributed to whoever last
      changed the `cron` line, so that line must be edited by a human account.
    - **All five set `display_report: true`, and none sets `show_full_output`.** The two are not interchangeable and the default of both is `false`, which is
      how the first dry run finished green having answered nothing: the report went to a temp file on a runner that then stopped existing. `display_report`
      publishes the agent's final report to the job summary; `show_full_output` dumps every intermediate tool result, and the action's own description warns it
      "may contain secrets, API keys, or other sensitive information" in a publicly visible log. The report itself is public in the step summary either way,
      which is the exposure the pull request body already carries by design — so it is a decision rather than an oversight, recorded here rather than four times. **A step summary has no REST API**, so each workflow also extracts the final
      report from the action's `execution_file` output and uploads it as an `agent-report` artifact — otherwise a nightly scheduled report would exist only as a
      browser page nobody opens. Only the final report is extracted, never the file itself: it holds every tool result, which is the thing
      `show_full_output`'s warning is about. A shape change in that file produces an empty artifact with an explanatory line, not a red job. **A run that produced no report then fails the job**, which is the
      guard the rest of this family kept needing. The agent's turn ends when it stops calling tools, so a closing line — "I'll compile the report once the
      checks finish" — reports success and delivers a sentence; that happened once, on an `--all` sweep that ended waiting for classification agents it had no
      tool to spawn. The check is under 400 bytes or fewer than five lines, measured against real output: the observed stub was 105 bytes on one line and a real
      report 5,207 bytes over 62. It runs **after** the upload so the stub survives for diagnosis, and only when the agent step itself succeeded, because a
      failure above is already red for a better reason. Every prompt's unattended section now also states the contract directly — the final message _is_ the
      report, and there is no second turn. **And every count in a report carries the command that produced it**, which is the one guard the stub check cannot
      supply: a well-formed report can be confidently wrong, and only a re-runnable command output cannot. Two `--all` runs minutes apart once disagreed about
      whether a pattern still existed in the tree — `git grep` settled it, and the run reporting zero was the wrong one. A zero needs its evidence, and so does every other
      number: a later run proved each of its zeros, left its one non-zero count unproved, and reported three RELOCATE candidates where the tree held
      fifty-seven — the unproved count was the only wrong one. **`/compact-comments` and `/update-dependencies` also gained the ship step neither had**: both edit files and neither had
      an `/open-pr` at the end, so a live run made changes that died with the runner while still reporting success. Every acting prompt here now ends the same way,
      and a sweep that applied nothing ships nothing rather than an empty pull request.
    - `agent-refactor.yml` — the `/refactor` workload, and the one whose prompt was written for this (#389, `.github/prompts/refactor.prompt.md`).
      Behaviour-preserving changes only, with the test suite as the proof. **`--unattended` fences it away from shared normalization** — `SlugGenerator`,
      `GenreNormalizer`, `ArtistNameMapping`, `MoneyExtensions` — because a change there compiles, passes the whole suite, and still changes the rows that land
      in the database. The only check that catches it is a `--full` re-seed, which needs a database and a live scrape, so findings in that code are reported and
      never applied. Second in the evaluation order, because a wrong answer is caught mechanically.
    - `agent-dependencies.yml` — the `/update-dependencies` workload, narrowed by `--unattended` to **Step 12 and nothing else**. Dependabot already owns
      `gradle`, `npm`, `docker`, `github-actions` and `opentofu`, so an agent re-deriving those bumps only produces a worse second pull request against the same
      files. Step 12's pins are the blind spot: a tool version pinned as a plain string belongs to no ecosystem, so `HELM_VERSION`, `ZIZMOR_VERSION`,
      `ACTIONLINT_VERSION` and their siblings rot while the checks that consume them keep reporting success. **`walg_version` and `k3s_version` are excluded** —
      they are force-new attributes and bumping either plans a node replacement — and so is Step 13, whose check is a k3d rehearsal the runner cannot run.
    - `agent-comments.yml` — the `/compact-comments` workload, and **the dangerous one**, in the terms #387 uses. A large share of the comments here exist to
      carry reasoning, and each reads as removable to something optimising for brevity. `--unattended` limits the run to **DELETE, RENAME and EXTRACT**, the
      buckets a reviewer checks in seconds; **RELOCATE and KEEP are reported, never applied**, and venue KDoc is off-limits whatever its density. **It sweeps with `--all`, not `--worst N`, and the first run is why.** A `--worst 5` sweep removed
      **zero lines safely** and explained itself: ranking by comment lines times ratio selects for the files that are dense _on purpose_ — venue KDoc, Gradle
      traps, metrics contracts — which is where the least is removable. Boilerplate is the opposite shape, a few lines each across many files, ranking nowhere,
      so only a whole-tree walk reaches it at all. The prompt therefore **caps the pull request at twelve files and reports the rest**, so a sweep converges
      over several runs rather than arriving unreviewable. Its default
      model is `claude-opus-4-8` rather than Opus 5, which is a measured preference about verbosity at comment work rather than a cost decision — the input
      exists so 4.6 and 4.8 can be compared on one prompt. Last to earn a schedule, per #387's ordering. It sets up a JDK and Gradle, because the proof
      obligation is a full build and a missing toolchain reads to a model as a broken one.
    - `cut-release.yml` — publishes the GitHub Release that `release.yml` keys on, then opens the pull request that moves `main` to the next snapshot (#868).
      `workflow_dispatch` only, `dry_run` on by default. Three things about it are decisions. **The version is never typed** — it comes from
      `scripts/version.sh base`, so a tag cannot claim a number the tree does not carry, and the same script writes the four files for the bump so a workflow
      and a person edit them identically. **It mints a GitHub App token rather than using `GITHUB_TOKEN`**, because GitHub suppresses the events its own token
      raises: a release created with it fires no `release: published`, so nothing is published and every job is green, and a pull request it opens starts no
      check, so the bump never merges. The token is narrowed with `permission-contents` and `permission-pull-requests` rather than inheriting the
      installation's, which zizmor's `github-app` audit is what enforces. **And it does both halves in one run**, because the bump is the step nobody notices
      missing: until `main` carries the next snapshot, staging keeps resolving the release itself (#455).
    - `validate-workflows.yml` — **actionlint** (correctness) and **zizmor** (security) over `.github/workflows/`, since #383. It is the only gate that looks at
      the workflows themselves, and on its first run zizmor found a template injection in `release.yml`, a cache-poisoning path into it, and two workflow-level
      permission grants that belonged to a single job. zizmor blocks at `--min-severity medium`; suppressions live in `zizmor.yml` or as inline
      `# zizmor: ignore[…]` comments, each with a reason and a date. **`unpinned-uses` is set to `hash-pin`** since #443 landed on 2026-08-18, so an action added
      with a tag fails the build — see the actions rule below.
    - `validate-docs.yml` — `scripts/format-markdown.sh check` over every `.md` file. It **checks and never writes**: a job that pushed a formatting commit back
      would need write access on every pull request including forks, which is far more than a formatter is worth, so a failure names the files and leaves the
      one-command fix to the author. It installs `events-frontend`'s dependencies for the pinned oxfmt rather than fetching a released one — versions disagree
      about Markdown, and a check run against "whatever is newest" would fail on files a contributor's pinned copy had just written. `package-lock.json` is in
      its path filters for that reason: an oxfmt bump can reformat every document here. **It keeps a `paths:` filter on `pull_request`** because it is not on
      the required list — see the note in the file, and delete the filter if it is ever made required. `validate-notices.yml` is the worked example of exactly
      that: it became required and lost its filter in one change.
    - `validate-notices.yml` — regenerates `events-frontend/src/assets/notices.json` and fails when the committed copy differs, via
      `scripts/notices-parity.sh check`. **It is its own workflow because the file merges two ecosystems**: the generator reads the Gradle licence report off
      disk and combines it with npm's, so a regeneration needs a JDK and Node in one job, and no other pull-request workflow has both. Folding it into
      `validate-docs.yml` would have meant putting every backend dependency file into that workflow's filter, triggering its three unrelated jobs on
      `gradle.properties`. **The `paths:` filter is the part that rots**: a dependency file missing from it means the check does not run for the change that
      moves the notices, which is the silent drift it exists to end. It only works at all because the generator writes no timestamp, so an unchanged
      dependency set regenerates byte-identically (#1037, after the file had drifted by 51 components unnoticed). **It is required on the `main` ruleset and so
      has no `pull_request` filter** — the two go together, and it runs on every pull request at 58s warm and 213-260s cold. That was priced and accepted
      (#1084) only because the workflow below removes the reason it would otherwise be red on bot branches.
    - `fix-notices-on-bot-prs.yml` — **the other half of the check above, and the reason it is not just noise on bot pull requests.** `validate-notices.yml`
      fails on every Dependabot or Renovate branch that moves a frontend dependency, and neither bot can satisfy it: regenerating needs a JDK and Node in one
      job. #1073 was the first, one day after the check shipped. This regenerates on the bot's branch and pushes, so the red tick means something again.
      Four decisions in it are load-bearing and each is written out in the file:
        - **`workflow_run`, not `push`.** A workflow triggered by a Dependabot push gets a read-only `GITHUB_TOKEN` and **cannot read repository secrets at
          all** — they live in a separate Dependabot store — so the token mint would fail on most branches. `workflow_run` runs in the default branch's
          context with the normal secrets.
        - **Not `pull_request_target`.** That is the other writable trigger, and it would hand a writable token to a job that checks out a branch and runs
          `npm ci` on its lockfile — the arbitrary-code-execution path `milestone-dependabot.yml` refuses in capitals.
        - **The App token is minted _below_ `npm ci`.** Install scripts from the branch's lockfile run before the credential exists, so a malicious package
          meets a job with nothing to steal. It carries `contents: write` and nothing else, and the commit stages one file by name.
        - **The commit author is not `renovate[bot]`.** Renovate decides whether a human touched a branch from the last commit's author, and would read its own
          name and force-push the regeneration away. Both bots abandon a branch once a foreign commit lands, which is the behaviour wanted here — at the cost
          that the bump no longer auto-rebases.
    - `validate-infra.yml` — `tofu fmt -check`, `tofu init -backend=false` + `validate` across all three stacks in a matrix, and ShellCheck on the cloud-init
      scripts. Triggers only when `infra/**` changes. **It deliberately never runs `plan`**: that needs a Hetzner token, and per PLATFORM_SETUP.md §4 nothing
      outside the cluster holds a cluster or cloud credential. So this is a syntax and type gate, not a correctness one, and there is no drift detection.
    - `build-frontend.yml` builds the frontend image the same way, from the `dist/` its own `npm run build` step already produced.
    - `build-backend.yml` also **builds both container images for `linux/amd64` and `linux/arm64` and deliberately does not push them.** It runs on every pull
      request, including from forks, so publishing here would put an image built from unreviewed code into GHCR — that is `release.yml`'s job.
      `outputs: type=cacheonly` because a multi-platform image cannot be loaded into the local daemon, and dropping to one platform would leave arm64 — the
      architecture the Hetzner nodes run — unbuilt. **Both workflows build images on pull requests only**, since `release.yml` builds and pushes the same three
      images on every push to `main` and doing it twice per merge buys nothing.
    - `release.yml` — **the only workflow that publishes anything.** Builds the three images and packages the chart from one computed version, scans the images
      with Trivy before pushing, and pushes to GHCR: a snapshot on every push to `main`, a release on a `v*` tag. **It does not deploy** — Flux pulls and
      reconciles (#414), so a green run means the artifacts exist, not that they are live. Three things about it are deliberate and easy to "fix" wrongly:
      **no path filters** (the chart's `appVersion` is the default image tag for all three components, so every published chart needs all three image tags to
      exist — a path filter here publishes a chart referencing images that were never built); **no tests** (they gate the PR); and **two builds per image**,
      because a multi-platform image cannot be loaded into the local daemon and therefore cannot be scanned before it exists in a registry. **It publishes on an
      allowlist** — `push` events, or a `workflow_dispatch` whose `publish` input is ticked — never "everything except the dry run", so a trigger added later
      cannot quietly become a publishing one. And it **tests itself on pull requests that change it**, because the `workflow_dispatch` caveat above applies to
      it with teeth: the button does not exist until the change merges, and merging is what publishes.
    - `validate-chart.yml` — `helm lint --strict` and `helm template` | `flux schema validate` across every values file and every cluster's Flux resources, plus
      `helm unittest` and `scripts/cluster-assertions.sh`. Triggers only when `deploy/**` changes. **Pins Helm 4.2.4**, matching the SDK helm-controller embeds, so the
      client that gates the chart is the one that installs it. It pinned Helm 3 until #1006, on a premise that had lapsed — and while it did, CI was weaker
      than the pre-commit hook, because Helm 4's `--strict` rejects an unknown `Chart.yaml` key and Helm 3's does not. Like `validate-infra.yml` it reaches no cluster, so it is a
      syntax and shape gate; the assertions are the part that catches a chart which is well-formed and wrong.
- **Every `uses:` names a commit SHA, never a tag** (#443, 2026-08-18). A tag is a pointer its owner can move, so a compromised action repository would reach
  every workflow here on its next run — and since #264 a run on `main` publishes three images and a chart. The form is the one Dependabot maintains:

    ```yaml
    uses: actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1
    ```

    **The comment is not decoration** — Dependabot reads it to know what version the SHA is, and rewrites both together, so the ongoing cost of SHA pinning is
    the same as the cost of tags. Adding an action by tag now fails `Lint & audit workflows`, which is a required check, so this cannot regress by review
    fatigue. GitHub's repository-level **`sha_pinning_required` is on** (2026-08-19), which enforces the same thing one layer down and is the belt to
    zizmor's braces — the check refuses the pull request, the setting refuses the run.

    **Read that setting from `repos/{owner}/{repo}/actions/permissions`, never from the repository object.** `repos/{owner}/{repo}` reports
    `sha_pinning_required: null` whatever its real value, which is the same shape that made #443's audit record private vulnerability reporting as off
    when it was on. A `null` there is not a `false`; it means the field is answered somewhere else.

    **Every `actions/checkout` also sets `persist-credentials: false`.** Without it the job's token is written into `.git/config`, where any later step — or an
    artifact upload of the workspace — can read it. No workflow here pushes with git, so nothing needs it. It is also required rather than optional in practice:
    zizmor's `artipacked` audit can read the version out of `@v7` and skip the check, but a SHA tells it nothing, so pinning without this turns 15 silent passes
    into 15 medium findings on a gate that blocks at medium.

- **Nine checks are REQUIRED on `main` and a pull request cannot merge without them** (#443, applied 2026-08-13). They were chosen for a specific reason: each
  runs on _every_ pull request, because GitHub keeps a required-but-skipped check `Pending` forever — _"a pull request that requires those checks to be
  successful will be blocked from merging"_ — so requiring a path-filtered check deadlocks every PR that does not touch its paths. #447 was a live example: it
  never ran `Lint & render` at all. The `pull_request` path filters on `validate-chart`, `validate-infra` and `validate-workflows` were removed so their checks
  always report; their combined cost is **54 seconds**.

    ```
    Lint & render · ShellCheck deploy-story scripts  validate-chart.yml
    Lint & audit workflows                           validate-workflows.yml
    Format & Validate (infra/bootstrap)              validate-infra.yml — a MATRIX, so one context per stack
    Format & Validate (infra/environments/staging)
    Format & Validate (infra/environments/production)
    ShellCheck cloud-init                            validate-infra.yml
    CodeQL · Dependency Review                       always run, unfiltered
    Build & Test (backend)                           build-backend.yml  — a gate job, see below
    Build & Test (frontend)                          build-frontend.yml — a gate job, see below
    ```

    **Three of these skip their work on a pull request that cannot affect them, without skipping the check.** `Build & Test (backend)`/`(frontend)` do it with a
    gate job; the three `Analyze (…)` contexts do it with a `relevance` step that gates every other step in the job. Both shapes exist for the same reason — the
    required context has to be produced by something unconditional — and both fail loudly rather than skipping when the change detection itself fails. A
    documentation-only pull request now costs seconds on all five instead of ~22 minutes. - **Adding a step to `codeql.yml`'s `analyze` job means adding `if: steps.relevance.outputs.value == 'true'` to it.** Without it the step runs on a
    docs-only pull request with nothing checked out, and fails for a reason that looks nothing like its cause. - **The path patterns live next to the thing they gate**, per language in `codeql.yml` and per workflow in the two build files. A change to `codeql.yml`
    itself matches all three languages, so a change to the patterns is always validated by the pull request that makes it.

    Two consequences worth knowing before changing any of this. **Adding a stack to `validate-infra`'s matrix creates a context that is not required** — the rule
    names each one exactly, so the matrix growing silently weakens the gate; add it to the ruleset in the same change. And **never add a `paths:` filter to the
    `pull_request` trigger of those three workflows** — it would block every unrelated pull request, and the failure looks like a hung check rather than a
    misconfiguration.

    **`Build & Test (backend)` and `Build & Test (frontend)` are required, and neither is the job that does the work.** The builds cost 382s and 597s, so
    requiring them directly would put +16½ minutes on every pull request including documentation-only ones. Instead each workflow is three jobs: a
    `detect-changes` job (seconds, no checkout — it reads the pull request's file list from the API), the real build gated on its output, and a **`gate` job
    that always runs and carries the required context**, failing when the build failed and passing when it was skipped. - **The required context must never be the conditional job itself.** A job skipped by a job-level `if:` does report a conclusion, but a job skipped by a
    workflow-level `paths:` filter creates no check run at all — and GitHub leaves an unreported required context Pending forever. The gate exists so the
    requirement sits on something unconditional, which is the same property the other required checks have. - **`detect-changes` failing is a blocked pull request, not a skipped build.** The gate refuses to report a pass unless detection succeeded, and
    detection fails on an empty file list. A change-detection bug must never present as a green build that did not run. - **The two job names were `Build & Test` in both workflows until they became required.** One context name cannot express "the backend one and the
    frontend one", so requiring it would have been ambiguous. Keep them distinct. - **`OWASP Dependency-Check` is deliberately not in the gate's `needs`**: it is informational and `continue-on-error`, and its `NVD_API_KEY` is empty on
    a fork run, so gating merges on it would turn an unauthenticated rate limit into a blocked pull request.

- **Commits are deliberately NOT signed** (#443). There is no `required_signatures` rule on the `main` ruleset and none is wanted: with a
  single maintainer, a signature proves authorship to the same person who holds the only account that can merge anything, while costing a signing key on every
  machine _and every agent_ that commits here. The control that actually gates `main` is the ruleset — every change by pull request, every
  required check green, no bypass actor. **The condition that would change this is a second committer**, not a change of mind; that is when a signature starts distinguishing one author
  from another. Recorded so it is decided rather than merely unexamined.

- **Every published image carries a buildx SBOM as well as its signed provenance attestation** (#443). `sbom: true` on the three `docker/build-push-action`
  steps in `release.yml`, chosen over `actions/attest-sbom` for reasons written out in a comment directly above them — read it before changing this. **The two
  attestations answer different questions and neither substitutes for the other**: the provenance statement is signed and says _where the image came from_, the
  SBOM is unsigned metadata on the image index and says _what is inside it_. Only the first is what `gh attestation verify oci://… --repo enorm-labs/event-junkie`
  checks, and it is quiet on success, so exit 0 is the verdict.

- **Releases are immutable** (#443, enabled 2026-08-19, before the first release existed so nothing was grandfathered in). A published release's tag and assets
  cannot be edited or deleted afterwards. This matters more here than it would elsewhere because **publishing a GitHub Release is what triggers a production
  publish** (#264): the tag that names a production image is now as permanent as the image it names, and a release published by mistake is fixed by shipping
  forward, never by deleting it and reusing the tag. Read it back from `repos/{owner}/{repo}/immutable-releases`.

- **Steps that verify come first; steps that report to GitHub come last. Never the other way round** (#507). `if:` on a step carries an implicit `success()`,
  so a failing step skips every step below it — and a _skipped_ step produces no annotation and no summary line, so the loss is invisible. Put anything that
  calls the GitHub API (`upload-sarif`, a PR comment, `github-script`) after everything that builds, tests or scans, and give each one `success() || failure()`
  so it still runs when something above failed and cannot take its siblings down with it.

    This has now bitten twice, both found during a GitHub incident and neither visible as what it was:

    - `build-backend.yml` posted the coverage comment **before** building the container images. An API error turned the job red for a cosmetic reason and
      silently dropped the multi-arch image build — on the one pull request (#506) that changed the JRE base image in both Dockerfiles.
    - `release.yml` uploaded the Trivy SARIF reports **before** publishing. Every publish step is guarded on `publish == 'true'`, which carries that implicit
      `success()`, so a Code Scanning hiccup meant images built, images scanned clean, chart packaged — and nothing pushed. On a push to `main` that is staging
      silently not receiving the chart, which is #455's failure mode reached from a different direction.

- **When CI misbehaves, check [githubstatus.com](https://www.githubstatus.com/) before debugging this repo.** Scriptable as
  `https://www.githubstatus.com/api/v2/summary.json`. A GitHub-side incident mimics repo-level bugs closely enough to send you hunting through trigger and path
  filters that are perfectly fine. Symptoms seen during the 2026-08-06 Actions outage:
    - **No run is created at all** for a PR — nothing to re-run, and `gh run rerun` cannot help. Trigger webhooks were throttled to ~15%. The tell-tale: a PR
      that gets no label either, since `label-pr.yml` was dropped by the same throttle.
    - **A run "fails" with zero steps executed**, annotated `The job was not acquired by Runner of type hosted even after multiple attempts`. That is runner
      starvation, _not_ a test failure — read the annotation before concluding the code is broken, and never merge past a red check without checking which of
      the two it is.
    - **Runs appear for branches deleted hours ago** as the throttled backlog replays. They are noise about the past, not signal about `main`.
    - Do not trust the **Webhooks** component on the status page: it read _Operational_ throughout, while the Actions incident text was the thing saying
      workflow-triggering webhooks were being dropped. Read the incident, not the component grid.
    - `gh run list --branch <name>` can look empty while `gh pr view --json statusCheckRollup` still shows CodeQL "Analyze" checks — CodeQL is GitHub's
      **default setup** (`event: dynamic`), which runs on a separate path from the workflow files here and so survives outages that stop everything else.
    - The `head_sha` filter on `/actions/runs` needs the **full 40-character SHA**; an abbreviated one silently returns `total_count: 0` and looks exactly like
      "no runs were created".
    - With CI unavailable, the honest fallback is a local `/verify` against the merged commit — and say in the PR that CI never ran, rather than implying a
      green build.
- **The required checks are a repository setting, and this file deliberately does not name their number.** It has drifted twice. Read the list from
  `gh api repos/{owner}/{repo}/rulesets` — **not** from `repos/{owner}/{repo}/branches/main/protection`, which answers `404 Branch not protected` here because
  the enforcement is a ruleset rather than classic branch protection. That `404` reads as "nothing is enforced" and is the trap worth knowing.
- **Dependabot** (`.github/dependabot.yml`) runs weekly across **six ecosystems**. Everything is grouped, because the alternative on a project this size is a
  pull-request queue nobody reads.
    - **`gradle`** (`/`) — grouped by library family: `kotlin`, `spring-boot`, `spring-modulith`, `testcontainers`, `jackson`, `springdoc`, `kotest`,
      `postgresql`, `flyway`, `reactor`, `detekt`, `owasp`, `gradle-plugins`.
    - **`npm`** (`/events-frontend`) — `versioning-strategy: increase`, which is what preserves the frontend's exact-pin convention: Dependabot rewrites the pin
      rather than widening it into a `^` range. Five families (`vue`, `linting`, `testing`, `typescript`, `tailwind`) keep toolchains that must move together in
      one PR, and `frontend-minor-patch` sweeps up the rest. **A dependency joins the first group it matches**, so the families must stay above the sweep in the
      file. Majors outside a family stay ungrouped deliberately — a Vite or Vue major deserves its own PR.
    - **`github-actions`** (`/`) — one group for all of them. `/` here does not mean the repository root in the usual sense; for this ecosystem Dependabot
      always reads `.github/workflows/`.
    - **`opentofu`** (`/infra/**`) — **not `terraform`**. They are separate ecosystems with separate registries, and the lock files there record providers as
      `registry.opentofu.org/…`, which the `terraform` updater would rewrite to `registry.terraform.io`. All four directories are grouped into one PR, since a
      single provider release otherwise opens four identical ones. Expect it to change **`.terraform.lock.hcl` and not `versions.tf`**: the `~> 1.68` constraint
      already permits 1.69, so the constraint is left alone until 2.0 while the lock file — which decides the version actually used — moves.
    - **`docker`** (`/events-bff`, `/events-importer`, `/events-frontend`) — the base image in every Dockerfile is pinned by tag **and** digest, and this is
      what keeps the digest from going stale. An unmaintained digest pin is a promise never to receive a security fix: the tag moves, the digest does not, and
      nothing says so. The three directories are grouped into one PR because the two backends pin the same base image.
      It updates the **tag** as well as the digest. The limit is not what it notices but what it can do: in #264 the Trivy gate found 10 fixable HIGH Alpine
      advisories in the frontend image, and Dependabot had already opened #437 proposing that exact bump — it was simply still open. **An open Dependabot PR is
      a live vulnerability**, and nothing in its title distinguishes one from a routine version bump. `release.yml`'s image scan is what turns an unmerged one
      into a failing build. **When bumping a base image, check the branch is still being rebuilt**, not just that a newer tag exists: `nginx 1.29` was a
      superseded mainline branch and had been shipping three-month-old Alpine packages.
    - **What no ecosystem covers: a tool version pinned as a plain string.** `HELM_VERSION`, `FLUX_VERSION`, `FLUX_SCHEMA_VERSION` and `HELM_UNITTEST_VERSION` in `validate-chart.yml`, `HELM_VERSION`
      and `TRIVY_VERSION` in `release.yml` **and in `image-scan-scheduled.yml`** (both pairs must move together — the two Trivy pins in particular, since the
      whole point of the scheduled scan is that its findings are comparable with the publish gate's), and gitleaks' `rev:` in `.pre-commit-config.yaml` belong
      to no Dependabot ecosystem — `github-actions` updates
      `uses: azure/setup-helm@v5` and has nothing to say about the `version:` handed to it. They rot silently, and a scanner a year behind still reports
      success. `/update-dependencies` step 12 sweeps them. **`HELM_VERSION` tracks the Helm SDK helm-controller embeds** — Helm 4 since
      v1.6.x — so that pin is a constraint, not a lag, and not "whatever `helm/helm` says is latest" either. It was held at 3.x on a lapsed premise until
      #1006.
    - **`docker-compose`** (`/`) — its own ecosystem in dependabot-core rather than a directory of `docker`, which reads Dockerfiles only. These are
      development services, so a stale pin never reaches production — but it decides what a local run and a Testcontainers test exercise, and a Postgres that
      drifts from the cluster's is a difference nobody sees until a deploy.
- **Renovate** (`.github/renovate.json5`) covers what belongs to no Dependabot ecosystem **and** is not a workflow string pin. **Three mechanisms watch
  versions here and they must not overlap**, because two bots proposing the same bump is worse than either alone (#384, ADR-024):

    | Mechanism                          | Owns                                                                                                                 | Because                                                                                |
    | ---------------------------------- | -------------------------------------------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------- |
    | **Dependabot**                     | the six ecosystems above                                                                                             | they are declared in a manifest it understands                                         |
    | **`/update-dependencies` Step 12** | tool versions pinned as plain strings in `.github/workflows/`                                                        | it can push them since #996                                                            |
    | **Renovate**                       | Flux and the charts it installs, images in plain Kubernetes manifests, `.pre-commit-config.yaml`, the Gradle wrapper | they belong to no ecosystem, and Renovate has purpose-built managers rather than regex |
    - **`enabledManagers` is an allow-list, and that is the whole safety argument.** A manager absent from it cannot open a pull request whatever it finds, so
      duplication against Dependabot is structurally impossible instead of a thing to police. Five are enabled: `flux`, `helm-values`, `kubernetes`,
      `pre-commit`, `gradle-wrapper`.
    - **Four of the five do nothing on their defaults, and three fail silently.** `flux` defaults to `gotk-components.yaml` alone; `kubernetes` defaults to
      matching nothing at all; `pre-commit` is disabled by default upstream, indefinitely. A manager that matches nothing reports nothing, which is the same
      failure this whole boundary exists to prevent.
    - **`managerFilePatterns` in a manager block is additive to that manager's default, not a replacement** — measured, not documented. To _exclude_ a file,
      use `packageRules` with `matchFileNames`. A scope you believe you set and did not is the expensive version of this mistake.
    - **`labels: ["dependencies"]` is load-bearing, not cosmetic.** `release.yml` sorts release notes by label and Renovate applies none by default, so
      without it every Renovate pull request lands in "Other Changes".
    - **The commit prefix is `chore(deps)` and is deliberately not configured.** An earlier `semanticCommitType: "build"` was inert — `config:recommended`
      pulls in `:semanticPrefixFixDepsChoreOthers`, whose `packageRules` entry sets `chore`, and a packageRule beats top-level config. Dependabot moved to
      `chore(deps)` on 2026-08-28 anyway, so the two agree without it. **Never key anything on the prefix**; the `dependencies` label is the stable signal.
    - **`milestone-dependabot.yml` matches on the bot's login**, so `renovate[bot]` had to be added to its `BOTS` set — otherwise every Renovate PR arrives
      with no milestone and the workflow logs the skip as normal operation.
    - `/update-dependencies` still exists and is not redundant: Dependabot proposes one bump at a time, while that skill does a deliberate sweep across both
      stacks and knows which Gradle versions are BOM-managed and must **not** be pinned.

- **A skill is three files, and `scripts/skill-parity.sh` is what keeps them in step.** The prompt lives in `.github/prompts/<name>.prompt.md`; `.claude/skills/`
  and `.claude/commands/` each hold a one-line `@` pointer to it; `CLAUDE.md` § Project skills lists it. Nothing joins those trees, so a skill added to one and
  not the other is **silently absent** from the other — no error, the command simply is not there, which is how four skills went without commands. The check
  asserts all three copies agree and that every pointer resolves. `/verify` and `validate-docs.yml` both run it.
- **Conventional Commits** — Commit messages follow the [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/) spec. Reusable prompts are
  available at `.github/prompts/` for commit messages, squash commit messages, and code reviews.
- **Release notes** (`.github/release.yml`) — GitHub's automatically generated release notes group merged PRs into categories (🎪 New Event Sources, ✨ Features,
  🐛 Bug Fixes, …) by the labels `label-pr.yml` applies. Categories are matched **in order**, first match wins, so specific ones (`importer`, `dependencies`)
  precede general ones (`feat`, `build`). Label a PR `ignore-for-release` to keep it out of the notes entirely.
- **Opening a PR** — the `/open-pr` skill (`.github/prompts/open-pr.prompt.md`) runs the full ship flow: cut a branch, commit with a Conventional Commits
  message, push, and open the PR via `gh`. Invoking it is the explicit go-ahead for the commit/push that the "no unsolicited commits/pushes" rule above
  otherwise withholds.

## Constraints on automating GitHub itself

Each of these looks like a bug in your workflow the first time you hit it. The `gh`-scripting counterparts — rate limits, label flags, stale merge state — are
in [AGENTS.md](../../AGENTS.md) § Automating GitHub with `gh`.

- **Fork pull requests work, and the property that makes them work is fragile** (#479 — first one opened _and merged_ 2026-08-19, #579). Every required check
  declares only `contents: read` and depends on no secret, so a fork's read-only `GITHUB_TOKEN` runs all of them — there is no required-but-skipped check,
  which is the failure mode that makes a pull request unmergeable forever and look like a broken repository to a first-time contributor. **Adding a secret or a
  `write` permission that a step actually depends on breaks the fork path**, and it breaks it invisibly, because pull requests from forks are rare enough here
  that nothing routinely exercises it.
    - **This is why `Build & Test`'s required context is the `gate` job rather than the build.** The build declares `pull-requests: write` and
      `security-events: write` for its coverage comment and SARIF uploads, both guarded on the fork path; the gate declares `contents: read` and nothing else,
      so the required context keeps the property above even though the job it reports on does not.

    **This was not a hypothetical: the path was broken when it was first tried.** `CodeQL` was required and ran through GitHub's _default setup_, which produces
    no run at all for a fork — no workflow run, no check suite, no check. Everything else was green and the pull request could never have merged, with nothing
    red to explain it. #581 moved CodeQL to an advanced-setup workflow (`.github/workflows/codeql.yml`) precisely because a workflow runs on the fork path and
    default setup does not. **Never move CodeQL back to default setup**, and read that file's header before changing its matrix — the job names are the required
    check contexts.

    **Every step that writes to GitHub is guarded on `github.event.pull_request.head.repo.fork != true`** rather than left to fail — the coverage comment and
    the three detekt SARIF uploads in `build-backend.yml`, the OWASP SARIF upload in the same file, and the SARIF uploads in `release.yml` and
    `image-scan-scheduled.yml`. None of them can work with a read-only token however they are written; unguarded, the API answers `403` and the step fails, so
    the guard is what keeps a fork pull request from going red for a reason its author did not cause. **Add the guard to any new step that posts a comment or
    uploads SARIF**, in the same change that adds the step.

    The cost is named in the workflow rather than left to be discovered: on the fork path `min-coverage-changed-files` does not run and detekt findings do not
    reach Code Scanning, so both are a review responsibility. The checks themselves still run and still fail the build.

    **One secret is referenced on the pull-request path**: `build-backend.yml` passes `NVD_API_KEY` to the OWASP job, which runs on `pull_request`. It is
    empty on a fork run, the scan is `continue-on-error`, and nothing fails — but the invariant is not "no pull request reaches a secret", it is "no pull
    request _depends_ on one".

    **Two workflows use `pull_request_target`** — `label-pr.yml` and `milestone-dependabot.yml` — and both are safe **only** because neither checks out or runs
    pull-request code. Each opens with the same banner saying so, because that property is one innocuous-looking `actions/checkout` away from being an
    arbitrary-code-execution path holding write scopes. `milestone-dependabot.yml` needs the trigger for a second reason: a `pull_request` run raised by
    Dependabot gets a read-only token, so it could not write a milestone at all.

- **Nothing running in CI can push to `main`.** The `main` ruleset requires every change to arrive by pull request, and its **only** bypass actor is
  `OrganizationAdmin`. The obvious workaround does not exist: GitHub refuses the Actions bot as a bypass actor with _"Actor GitHub Actions integration must be
  part of the ruleset source or owner organization"_ — a platform constraint, not a permissions problem, and the UI offers no such actor either. **Design any
  workflow that wants to write to the repo as generate-on-demand or open-a-PR, never as push-to-main.** A whole snapshot workflow was written, merged and
  deleted before this was discovered.
