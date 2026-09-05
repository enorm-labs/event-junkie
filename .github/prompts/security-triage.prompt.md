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

# Code scanning — open, all tools.
# `GH_TOKEN` is set here because unattended the ambient one is the Claude App's, which holds no
# `security_events` permission and answers 403. `ACTIONS_GITHUB_TOKEN` is the Actions token that
# `agent-security.yml` grants `security-events: read` to; run by hand it is unset and `gh` falls
# back to your own credentials, which is why the assignment is guarded rather than unconditional.
GH_TOKEN="${ACTIONS_GITHUB_TOKEN:-$GH_TOKEN}" \
gh api "repos/enorm-labs/event-junkie/code-scanning/alerts?state=open&per_page=100" --paginate \
  --jq '.[] | [.number, .tool.name, (.rule.security_severity_level // .rule.severity), .rule.id,
        (.most_recent_instance.location.path // "-")] | @tsv'
```

Four things to normalise before deciding anything. Each has misled a reading of this output:

- **Dependency-Check duplicates heavily** — one CVE is attributed to every artifact matching the CPE, so 25 alerts can be one problem. Group by CVE/GHSA.
- **Dependency-Check paths are not repo paths.** They look like `file:///home/runner/.gradle/caches/modules-2/…/foo.jar/META-INF/…`. Do not apply the
  test-fixture rule to them — judge them by artifact, not by path.
- **`manifest_path` does not tell you where a Gradle dependency is declared.** Every Gradle alert reports `settings.gradle.kts`, because that is the root
  manifest of the graph `dependency-submission.yml` uploads — not the file holding the version. Find the real one from `gradle.properties`,
  `settings.gradle.kts` or the module build script, per the classes in Step 3.
- **`first_patched_version` is sometimes a pre-release.** A live example: the `kotlin-gradle-plugin` alert's first patched version is `2.4.20-Beta1`. Bumping to
  it would violate `/update-dependencies`' stable-only rule, so a finding whose only fix is a beta is **not** a cheap fix — it is an issue, or an accepted risk
  until the stable lands. Check the shape of the version string before classifying.

## Step 2 — Decide, per finding

Take these in order. The first that matches wins.

1. **Is it already handled?** An open Dependabot PR, an open issue (`gh issue list --label area:security`), an existing suppression. If so, leave it and say so.
   **This check is not optional** — see the trap in Step 3.
2. **Can it be fixed cheaply? Then fix it, whether or not it affects us.** Cheap means: a version bump in a file we own, no API change, no migration, and
   `/verify`'s relevant subset stays green. A five-minute bump on a finding that could never reach production is still cheaper than the paragraph explaining why
   it was dismissed — and it removes the line permanently instead of leaving a dismissal someone re-reads in six months.
3. **Is it in the test HTML fixtures?** → dismiss as `used in tests` (Step 4). Path under `events-importer/src/test/resources/scraper/`.
4. **Have you established it cannot reach us?** → dismiss with the evidence (Step 4). "Established" means an advisory read and a version compared, not an
   impression.
5. **Otherwise it is real and not cheap** → file an issue (Step 5). Do not half-fix it.

## Step 3 — Fixing

**Check for an open Dependabot PR before touching a manifest.** Dependabot has standing PRs here (frontend groups, docker, actions), and hand-bumping a package
it is already bumping produces a conflict for whichever lands second — plus a duplicated review. If a PR exists, the fix is to merge or rebase _that_.

Then by class:

- **npm (`events-frontend/`)** — `npm install <pkg>@<version>` and commit the lockfile. Exact versions, no `^`/`~` (see `/update-dependencies`).
- **Gradle, project-managed** — a `*.version` property in `gradle.properties` or a plugin version in `settings.gradle.kts`. An ordinary bump.
- **Gradle, BOM-managed** — cannot be bumped as a normal dependency. Set the BOM's own property name in `gradle.properties`, under the
  "Spring Boot BOM overrides (CVE remediation)" block, and say which CVE justifies it. These are temporary by construction — `/update-dependencies` prunes them
  once the BOM catches up, and an override kept past its purpose is a silent downgrade.
- **Transitive with no BOM entry** — check first whether bumping the _direct_ dependency carries the fix. Only pin via a `constraints` block if it does not.
- **A webjar or other bundled asset** — the vulnerable file is inside a jar (the `swagger-ui` bundled JS is the live example). Bump the webjar; there is nothing
  to patch in our tree.
- **GitHub Action** — Dependabot's `github-actions` ecosystem owns the pins; prefer its PR.
- **Base image** — a Trivy finding in a path like `usr/bin/pebble` or `usr/lib/libuuid.so.1` is a binary _inside the image_, not code we wrote, so there
  is nothing in this tree to patch. Dependabot's `docker` PR is the first lever when one is open, and it has been the wrong one twice: when Alpine
  has the fix and the base has not been rebuilt with it, no PR is coming. That case is [§ A blocked publish](#a-blocked-publish), below.

After fixing, **prove the finding is gone** rather than assuming: re-resolve and compare against the advisory's `first_patched_version`.

### Resolve across _all_ configurations, not just `runtimeClasspath`

This is the step that decides whether an alert is real, and the obvious command gets it wrong. `--configuration runtimeClasspath` answers "does this ship",
which is the right question for a fix — but an alert can be raised against a configuration that never ships, and then `runtimeClasspath` looks clean while the
alert stays open and inexplicable. Drop the flag to see every configuration, and find the one that holds the old version:

```sh
./gradlew -q :events-core:dependencies | awk '/^[a-zA-Z].*- / { cfg=$0 } /<artifact>:<old-version>/ { print cfg " ||| " $0 }'
```

On the first real run (2026-08-14) this is what separated five alerts from two. Every runtime classpath was clean, and the old versions lived in exactly two
non-shipping places: **`logback-core:1.3.16` on the `ktlint` tool classpath** and **`log4j-api:2.25.4` on `testFixturesCompileClasspath`**, both in
`events-core`. Without naming the configuration, the only honest verdicts available are "stale alert" (wrong) or "still vulnerable" (also wrong).

Note which classpaths a tool plugin drags in: ktlint, detekt and the Dependency-Check plugin each resolve their own tool dependencies, entirely outside the
Spring BOM's reach, so a `gradle.properties` override does not touch them.

## Step 4 — Dismissing

Code scanning:

```sh
gh api -X PATCH repos/enorm-labs/event-junkie/code-scanning/alerts/<number> \
  -f state=dismissed -f dismissed_reason='used in tests' \
  -f dismissed_comment='Scraped fixture for the <venue> importer. Inert test data, never served.'
```

`dismissed_reason` is one of `false positive`, `won't fix`, `used in tests`.

Dependabot:

```sh
gh api -X PATCH repos/enorm-labs/event-junkie/dependabot/alerts/<number> \
  -f state=dismissed -f dismissed_reason=not_used \
  -f dismissed_comment='<evidence>'
```

`dismissed_reason` is one of `fix_started`, `inaccurate`, `no_bandwidth`, `not_used`, `tolerable_risk` — a **different vocabulary** from code scanning's, and
the API rejects the wrong one.

**`dismissed_comment` is capped at 280 characters on Dependabot alerts**, and the API rejects the whole request with a 422 rather than truncating. Draft to fit:
artifact, resolved version, where the vulnerable version does still appear, and the date. Code scanning's comment has no such limit, so do not copy a long one
across. (Found the hard way on the first real run, 2026-08-14 — four dismissals failed after the first succeeded.)

**The comment is the whole value of the dismissal.** It is the only thing a future reader has when the same alert reappears on a new version. Name the artifact,
the version compared and the reason it cannot reach us. "Not applicable" is not a comment.

### The test fixtures, and the fix that must not be attempted

Every importer ships captured HTML under `events-importer/src/test/resources/scraper/`, and real venue pages carry real inline scripts. CodeQL reads them as
JavaScript and raises `js/xss-through-dom`, `js/functionality-from-untrusted-source` and friends. **60 alerts have been dismissed as `used in tests` on this
basis already**, and `/scaffold-importer` guarantees more with every venue added.

The obvious structural fix — switch CodeQL to advanced setup and add `paths-ignore` — **must not be done casually, because it would block every pull request.**
Verified 2026-08-14: CodeQL runs as **default setup**, and **`CodeQL` is a required status check on the `main` ruleset**. Advanced setup reports per-language
`Analyze (…)` contexts instead, so the required `CodeQL` context would never report again and every PR would sit Pending forever — the same failure #443
documents. If the toil is worth removing, that is an issue with a plan (retire the required context in the same change), not a quick edit during triage.

## Step 5 — Filing what cannot be fixed now

Use [`/new-issue`](new-issue.prompt.md) — it owns the forms, the closed label vocabulary and the board fields. For these, `area:security` always, plus the area
the fix lands in. Put the CVE/GHSA, the resolved version, the fixed version and _why it was not fixed here_ in the body; a security issue without the version
comparison is one someone has to re-research.

Check for a duplicate first. Cross-cutting CVEs get filed once, not once per affected artifact.

## Step 6 — Verify and ship

Run the relevant subset of [`/verify`](verify.prompt.md) for whatever was touched — a dependency bump that breaks the build is a worse outcome than the
vulnerability it closed. Then [`/open-pr`](open-pr.prompt.md).

Alert dismissals take effect immediately and are not part of the PR. Say so in the report, because the two halves land at different times.

## Output

- **What was fixed**, with the CVE, the before/after version, and how it was verified.
- **What was dismissed**, with the reason code and the evidence — separating the pre-authorized dismissals from any the user approved.
- **What was filed**, with issue numbers.
- **What was left**, and why — including anything blocked on an open Dependabot PR.
- **Gate status**, explicitly: whether the nightly CVSS gate and the release Trivy scan would now pass. Remember these do not follow alert dismissals, so a
  clean Security tab is not an answer to this question.

## Notes

- **A clean Security tab is not evidence of safety.** `/security-report`'s [Why green means nothing here](security-report.prompt.md#why-green-means-nothing-here)
  applies in full — a Dependency-Check run that scanned zero dependencies produces exactly the same empty list as a healthy one. If the inventory comes back
  suspiciously quiet, confirm the scans actually ran before reporting good news.
- **Pure code-quality findings are not here.** CodeQL runs the `default` query suite, which is security-focused. Style, complexity and maintainability belong to
  detekt, ktlint and [`/code-review`](code-review.prompt.md); breadth-first quality work belongs to [`/codebase-audit`](codebase-audit.prompt.md).
- **Do not batch-dismiss by rule id.** The fixture rule is a _path_ rule. The same `js/xss-through-dom` on frontend source is a real finding, and a
  rule-wide dismissal would bury it.
