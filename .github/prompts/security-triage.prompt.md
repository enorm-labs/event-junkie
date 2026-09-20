# Security Triage

Work the [Security tab](https://github.com/enorm-labs/event-junkie/security) down to zero: **fix what is cheap, file what is not, dismiss what does not apply.**
Covers [Dependabot alerts](https://github.com/enorm-labs/event-junkie/security/dependabot) and
[code scanning](https://github.com/enorm-labs/event-junkie/security/code-scanning) — CodeQL, Trivy and OWASP Dependency-Check.

This is the **acting** counterpart to [`/security-report`](security-report.prompt.md), which investigates the same ground and never mutates. Use that one to
understand the position; use this one to change it.

## Important

- **This command mutates.** It bumps dependencies, opens issues and dismisses alerts. Invoking it **is** the permission to do those things — the same way
  [`/open-pr`](open-pr.prompt.md) is the permission to commit and push. Do not invoke it on your own initiative.
- **Dismissal is not uniform, and this is the one judgement call that matters.** Two tiers:
    - **Pre-authorized — dismiss without asking:** findings in the test HTML fixtures, and findings you have positively established cannot reach us, _with the
      evidence written into the dismissal comment_.
    - **Ask first:** anything `high` or `critical`, anything with a plausible path to production, and anything where "does not affect us" rests on an assumption
      rather than a fact. A wrong dismissal is invisible — nobody re-reads a dismissed alert — which is exactly why it needs a higher bar than a wrong fix.
- **Dismissing an alert is not the same as passing the gate.** For two of the three tools the alert and the gate are separate systems; see below. Dismissing the
  alert and declaring victory leaves the nightly scan red.
- `gh` needs the alerts scope. `gh api …/dependabot/alerts` returning `403` and returning `[]` look identical if you only check the length —
  `gh auth refresh -s security_events` if you see one.
- Run `git` and `gh` non-interactively (`git --no-pager …`); see AGENTS.md.

## Running unattended

[`agent-security.yml`](../workflows/agent-security.yml) invokes this prompt as `/security-triage --unattended` from a scheduled runner, where the "ask first"
tier above has nobody to ask. **Unattended, that tier does not collapse into the pre-authorized one — it collapses the other way.**

- **Dismiss nothing. Not one alert, not even a fixture finding this prompt would otherwise pre-authorize.** Dismissal is the single irreversible act here and
  the only one no reviewer will ever re-read. List the candidates in the pull request body, with the evidence each would have carried, and leave the API call to
  a human running this command by hand.
- **File nothing.** Step 5's `/new-issue` needs judgement about duplicates and board fields that is not available from a runner, and an agent filing issues on a
  schedule is how a tracker stops being read. What would have been an issue becomes a section of the pull request body.
- **Fix what the prompt already calls cheap**, and nothing else. A version bump with a matching advisory is in scope; a refactor to remove a vulnerable call
  path is not, and is one of the sections above.
- **`--dry-run` on top of it opens no pull request at all** and writes the whole report to the job summary. That is the mode to use the first time, and after
  any change to this section.
- **`--failed-publish <run-url>` names a red `release.yml` run on `main`**, which is the second trigger `agent-security.yml` has. Start from
  [§ A blocked publish](#a-blocked-publish), with the run's image and package, and do the rest of the inventory after. The run can arrive minutes
  before the nightly schedule or minutes after it, so **Step 2's "already handled" check includes a pull request this prompt opened earlier tonight** —
  `gh pr list --search 'author:app/claude'` — and a second run for the same finding reports it rather than opening a twin.
- **A waiver is the one thing here that is a judgement, and unattended it has the higher bar:** the three conditions in § A blocked publish, or no pull
  request. The upgrade line and the deletion are mechanical and in scope.
- **Report the reachability of each surface, not just its findings.** Dependabot alerts are expected to return `403` from Actions: neither `GITHUB_TOKEN` nor
  the Claude GitHub App carries a permission for them. Say which surfaces answered, because the Notes below are exactly right that a quiet inventory and a
  clean one look identical.
- **Code scanning answers only through `ACTIONS_GITHUB_TOKEN`**, and the inventory command in Step 1 already does that. The ambient `GITHUB_TOKEN` and
  `GH_TOKEN` are both the Claude App's, which has no `security_events` permission — so a bare `gh api …/code-scanning/alerts` returns `403` here even though
  the workflow grants the permission (#1021). If that variable is unset, say the surface was unreachable rather than reporting an empty list.

The output contract is unchanged; it lands in the pull request body rather than a terminal. **What was dismissed** becomes _what a human should consider
dismissing_, and keeps the evidence either way.

**Your final message is the report, and there is no second turn.** The run ends the moment you stop calling tools, so a closing line like _"I'll compile the
report once the checks finish"_ ends it with that sentence as the whole deliverable — and the job still reports success. There is nobody to hand off to and
nothing to wait for: no reviewer reads the transcript, no follow-up prompt arrives, and any work you plan but do not do in this turn is simply lost. Finish the
work, then write the Output section below as your last message. This has already happened once, on a `--all` sweep that ended waiting for classification agents
it had no tool to spawn.

**Every count in the report carries the command that produced it, and a zero needs its evidence like every other number.** A run that proved each of
its zeros and left its one non-zero count unproved reported three candidates where the tree held fifty-seven; two runs minutes apart once disagreed about
whether a pattern existed at all, both confident, one wrong. A number without a command behind it is a guess, and a guess in a section headed _"reported for
a human"_ is the one a human acts on. Command output is the only part of a report that cannot be plausible and false at the same time.

## Where each finding actually lives

Getting this table wrong is how a triage session ends with a clean Security tab and a still-failing nightly build:

| Surface                | Raised by                        | What blocks a build                                              | Silencing it means                                |
| ---------------------- | -------------------------------- | ---------------------------------------------------------------- | ------------------------------------------------- |
| Dependabot alerts      | GitHub Advisory DB               | nothing directly — `dependency-review.yml` gates _new_ deps only | the alerts API, or a version bump                 |
| CodeQL                 | code scanning, **default setup** | the **required** `CodeQL` status check                           | the alerts API                                    |
| Trivy                  | `release.yml`, image scan        | the release job                                                  | `.trivyignore` — **not** the alerts API           |
| OWASP Dependency-Check | `dependency-check-scheduled.yml` | the nightly CVSS ≥ 7 gate                                        | `owasp-suppressions.xml` — **not** the alerts API |

For Trivy and Dependency-Check the code-scanning alert is a _view_; the Gradle plugin and the Trivy step read their own files and neither knows the alert was
dismissed. Editing those two files is an accepted-risk decision — draft it and confirm with the user, as `/security-report` says.

## A blocked publish

The case where "prefer Dependabot's PR" is the wrong lever, and it has happened twice: #964 on 2026-09-01 (libexpat) and #1117 on 2026-09-05
(util-linux). Alpine publishes a fix, the nginx base has not been rebuilt with it, every mainline tag resolves to the digest already pinned, and every
`release.yml` run on `main` fails at _Scan the images_ until somebody acts. Nothing reaches staging, and `cut-release.yml` refuses to cut.
[RELEASING.md § Publishing is blocked](../../docs/ops/RELEASING.md#publishing-is-blocked) is the procedure for a person; this is the same procedure
with its commands. **`agent-security.yml` runs it on a failed publish**, passing `--failed-publish <run-url>`, and the last step runs on every invocation
whether or not a run was named.

**Every check is on amd64, and that is the whole lesson of #1118.** The gate scans the `linux/amd64` image, and Alpine builds each architecture
separately, so a package can exist for aarch64 and not for x86_64. #1118 was verified on an arm64 machine, merged, and upgraded nothing in CI.

1. **Read the finding from an image you built, not from the run's log.** The run names the image and the package; the rebuild is what you can act on.
   The frontend is the image that has failed both times, and its context needs only a stub `dist/`:

    ```sh
    mkdir -p events-frontend/dist && touch events-frontend/dist/index.html
    docker build --no-cache --platform linux/amd64 -t probe:amd64 events-frontend
    # Trivy at release.yml's pin, so the number is the gate's number
    v=$(sed -nE 's/^ *TRIVY_VERSION: *//p' .github/workflows/release.yml)
    curl -fsSL "https://github.com/aquasecurity/trivy/releases/download/v${v}/trivy_${v}_Linux-64bit.tar.gz" | sudo tar -xz -C /usr/local/bin trivy
    trivy image probe:amd64 --severity CRITICAL,HIGH --ignore-unfixed --ignorefile /dev/null   # the image
    trivy image probe:amd64 --severity CRITICAL,HIGH --ignore-unfixed --ignorefile .trivyignore # the gate
    ```

    The first number is what the image carries; the second is what blocks. Both go into the pull request. A BFF or importer finding is the same shape
    after `./gradlew :events-bff:bootJarLayers`, with `events-bff/build/docker` as the context.

    **`--no-cache` is not optional.** A `RUN apk upgrade` layer is cached by its text, so a cached build replays the index from the day the layer was
    first built and says nothing about today's. The rehearsal of this section found 7 HIGH with the cache and 0 without, on the same Dockerfile, the
    same afternoon — the cached layer predated Alpine's x86_64 build of the fix.

2. **Has the base moved?** Resolve the tag and compare with the `FROM` pin:

    ```sh
    docker buildx imagetools inspect nginxinc/nginx-unprivileged:1.31-alpine | grep -m1 Digest
    ```

    If it moved, build from the new digest and repeat step 1 — **the package decides, never the digest.** #770 was triaged against a digest that had
    moved while still shipping the vulnerable openssl. A clean rebuild is a one-line pin bump, or Dependabot's `docker` PR if one is already open.

3. **Does Alpine have the fix for x86_64?** Ask the index the gate's architecture reads, not `apk` on the machine you happen to be on:

    ```sh
    for a in x86_64 aarch64; do
      printf '%s ' "$a"
      curl -s "https://dl-cdn.alpinelinux.org/alpine/v3.24/main/$a/APKINDEX.tar.gz" | tar -xzOf - APKINDEX | grep -A1 '^P:<package>$' | grep '^V:'
    done
    ```

    The branch (`v3.24`) is in the Trivy header, `(alpine 3.24.1)`. Report both architectures; the difference between them is the finding of #1119.

4. **x86_64 has it → upgrade the package in our layer.** #964 is the shape and #1118 its second use: `USER root` and `RUN apk upgrade --no-cache <package>`
   above the `COPY`s, the final `USER 10001:10001` untouched. Named rather than a blanket `apk upgrade`, so what moves against the pinned digest stays
   reviewable. The comment carries the CVEs, why a digest bump was not available, the issue, and **the deletion condition with its check** — the base
   digest carrying the version, read with `apk list --installed` inside it. Prove it with step 1 on the new build before opening the pull request.

5. **x86_64 does not have it → a dated waiver in `.trivyignore`,** in #1119's shape, and only with all three of:
    - **the reachability check written into the reason**: `apk info -L <package>` inside the image lists what the package put there, and the reason names
      which of those files the advisory is in and why nothing executes it (`/bin/mount` being BusyBox is the #1119 argument);
    - **the deletion condition**: the x86_64 index listing the fixed version, which is step 3 re-run;
    - **and the file is not one the image executes.** A finding in nginx, a module it loads, musl or libssl is not waivable by this prompt. Write it up
      as _what a human should consider_, with the evidence, and open no pull request for it.

    The upgrade layer from step 4 goes in alongside the waiver when x86_64 is merely late: it fixes arm64 today and takes effect on amd64 the moment the
    index moves, which is what makes the waiver deletable without a second Dockerfile change.

6. **The inverse, on every run.** An upgrade line or a waiver kept past its purpose hides that the base has caught up and quietly becomes the thing
   choosing the version. For each `RUN apk upgrade` in a Dockerfile, run the check its comment names against the pinned digest; for each dated
   `.trivyignore` block, re-run step 3. A condition that holds is a deletion pull request in #1033's shape, with the check's output in the body. Check
   both even on a run with nothing red — that is how #1033 was found by hand, two days after the base had caught up.

**Two waivers sharing a root cause is the signal that the base image is wrong, not the waivers.** `.trivyignore` held eight entries for the Go stdlib
compiled into `usr/bin/pebble`, a binary Temurin's Ubuntu JRE carried and nothing executed, each one justified and dated, until #492 changed the base to
[Liberica on Alpine](../../docs/adr/ADR-017_JRE_BASE_IMAGE.md) and deleted all eight. The third lever — change the base — is the one that gets forgotten,
and it is a report and an issue, never an unattended pull request.

## Step 1 — Take the inventory

```sh
# Dependabot — open only
gh api repos/enorm-labs/event-junkie/dependabot/alerts --paginate \
  --jq '.[] | select(.state=="open") | [.number, .security_advisory.severity, (.security_advisory.cve_id // .security_advisory.ghsa_id),
        .dependency.package.name, .dependency.manifest_path,
        (.security_vulnerability.first_patched_version.identifier // "none")] | @tsv'

# Code scanning — open, all tools. Unattended the ambient token is the Claude App's, which holds no
# `security_events` permission and answers 403; ACTIONS_GITHUB_TOKEN is the Actions token. By hand it
# is unset and gh falls back to your own credentials, which is why the assignment is guarded.
GH_TOKEN="${ACTIONS_GITHUB_TOKEN:-$GH_TOKEN}" \
gh api "repos/enorm-labs/event-junkie/code-scanning/alerts?state=open&per_page=100" --paginate \
  --jq '.[] | [.number, .tool.name, (.rule.security_severity_level // .rule.severity), .rule.id,
        (.most_recent_instance.location.path // "-")] | @tsv'
```

Normalise before deciding: **Dependency-Check duplicates heavily** (one CVE per matching artifact — group by CVE/GHSA) and **its paths are Gradle cache paths,
not repo paths** (judge by artifact; the fixture rule does not apply); **`manifest_path` is always `settings.gradle.kts`** for a Gradle alert, so find the real
declaration in `gradle.properties`, `settings.gradle.kts` or the module script; **`first_patched_version` can be a pre-release** (`2.4.20-Beta1` was one), and
a finding whose only fix is a beta is not a cheap fix — an issue, or an accepted risk until the stable lands.

## Step 2 — Decide, per finding

In order; the first match wins. **Already handled?** — an open Dependabot PR, an open issue (`gh issue list --label area:security`), an existing suppression:
leave it and say so. **Cheap to fix?** — a version bump in a file we own, no API change, no migration, the relevant `/verify` subset green: fix it, whether or
not it affects us; a five-minute bump beats the paragraph explaining a dismissal. **In the test HTML fixtures** (`events-importer/src/test/resources/scraper/`)?
— dismiss as `used in tests`. **Established it cannot reach us** — an advisory read and a version compared, not an impression? — dismiss with the evidence.
**Otherwise real and not cheap** — file it; never half-fix.

## Step 3 — Fixing

**Check for an open Dependabot PR before touching a manifest**; if one exists, merge or rebase _that_. Then by class: **npm** — `npm install <pkg>@<version>`,
exact pin, commit the lockfile; **Gradle, project-managed** — the `*.version` property or plugin version; **Gradle, BOM-managed** — the BOM's own property name
in `gradle.properties` under "Spring Boot BOM overrides (CVE remediation)", naming the CVE (temporary; `/update-dependencies` prunes it); **transitive with no
BOM entry** — bump the direct dependency first, a `constraints` block only if that does not carry the fix; **a webjar** — bump the webjar (`swagger-ui`'s
bundled JS is the live example); **a GitHub Action** — Dependabot's PR; **a base image** — a path like `usr/lib/libuuid.so.1` is a binary inside the image,
Dependabot's `docker` PR is the first lever, and when Alpine has the fix and the base has not been rebuilt no PR is coming: [§ A blocked publish](#a-blocked-publish).

After fixing, **prove the finding is gone**: re-resolve and compare against `first_patched_version`. **Resolve across _all_ configurations, not just
`runtimeClasspath`** — an alert can be raised against a configuration that never ships, and then `runtimeClasspath` looks clean while the alert stays open:

```sh
./gradlew -q :events-core:dependencies | awk '/^[a-zA-Z].*- / { cfg=$0 } /<artifact>:<old-version>/ { print cfg " ||| " $0 }'
```

That is what separated five alerts from two on the first run: `logback-core` on the `ktlint` tool classpath, `log4j-api` on `testFixturesCompileClasspath`.
ktlint, detekt and Dependency-Check resolve their own tool dependencies outside the BOM's reach, so a `gradle.properties` override does not touch them.

## Step 4 — Dismissing

```sh
gh api -X PATCH repos/enorm-labs/event-junkie/code-scanning/alerts/<number> \
  -f state=dismissed -f dismissed_reason='used in tests' \
  -f dismissed_comment='Scraped fixture for the <venue> importer. Inert test data, never served.'
gh api -X PATCH repos/enorm-labs/event-junkie/dependabot/alerts/<number> \
  -f state=dismissed -f dismissed_reason=not_used -f dismissed_comment='<evidence>'
```

Code scanning takes `false positive`, `won't fix`, `used in tests`; Dependabot takes `fix_started`, `inaccurate`, `no_bandwidth`, `not_used`,
`tolerable_risk` — the API rejects the wrong vocabulary. **Dependabot's `dismissed_comment` is capped at 280 characters and a longer one is a 422**, not a
truncation. **The comment is the whole value of the dismissal**: artifact, version compared, why it cannot reach us, the date. "Not applicable" is not a comment.

**The fixtures, and the fix that must not be attempted.** Captured venue pages carry real inline scripts; CodeQL raises `js/xss-through-dom` and friends on
them, dozens are dismissed as `used in tests`, and `/scaffold-importer` adds more. Switching CodeQL to advanced setup with `paths-ignore` **would block every
pull request**: `CodeQL` is a required context produced by default setup, and advanced setup reports `Analyze (…)` contexts instead. That is an issue with a
plan that retires the required context in the same change, not a triage edit.

## Step 5 — Filing what cannot be fixed now

[`/new-issue`](new-issue.prompt.md), `area:security` plus the area the fix lands in, with the CVE/GHSA, the resolved version, the fixed version and why it was
not fixed here. Duplicate check first; a cross-cutting CVE is filed once.

## Step 6 — Verify and ship

The relevant subset of [`/verify`](verify.prompt.md), then [`/open-pr`](open-pr.prompt.md). Dismissals take effect immediately and are not part of the PR —
say so.

## Output

- **What was fixed**, with the CVE, the before/after version, and how it was verified.
- **What was dismissed**, with the reason code and the evidence — separating the pre-authorized dismissals from any the user approved.
- **What was filed**, with issue numbers.
- **What was left**, and why — including anything blocked on an open Dependabot PR.
- **Gate status**, explicitly: whether the nightly CVSS gate and the release Trivy scan would now pass. Remember these do not follow alert dismissals, so a
  clean Security tab is not an answer to this question.

## Notes

- **A clean Security tab is not evidence of safety** — [`/security-report` § Why green means nothing here](security-report.prompt.md#why-green-means-nothing-here):
  a scan of zero dependencies produces the same empty list as a healthy one. A quiet inventory means confirm the scans ran.
- **Pure code-quality findings are not here**; CodeQL's `default` suite is security-focused. Style belongs to detekt, ktlint, [`/code-review`](code-review.prompt.md).
- **Do not batch-dismiss by rule id.** The fixture rule is a _path_ rule; the same `js/xss-through-dom` on frontend source is real.
