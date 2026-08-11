# Security Report

Pull the project's current dependency-vulnerability position into one prioritized report: the latest **OWASP Dependency-Check** findings and **GitHub's
Dependabot alerts**, reconciled against each other and against what we have already triaged. This is a **read-only investigation** — inspect and report, never
mutate. Propose fixes; do not apply them unless the user explicitly asks.

## Important

- Run git and `gh` non-interactively (`git --no-pager …`); see AGENTS.md.
- Never edit `owasp-suppressions.xml`, bump a dependency, or open a PR as part of this command. Suppressing a finding is an accepted-risk decision that belongs
  to the user.
- **A green scan is not evidence of safety.** Read [Why green means nothing here](#why-green-means-nothing-here) before reporting anything reassuring.

## How the CI security jobs actually behave

Three workflows touch this area, and they fail in three different ways. Getting this wrong makes the whole report misleading:

| Workflow                                   | Trigger            | Fails the run?                             | Notes                                                             |
|--------------------------------------------|--------------------|--------------------------------------------|-------------------------------------------------------------------|
| `build-backend.yml` (dependency-check job) | PR / push to main  | **No** — step is `continue-on-error: true` | Informational only. Uploads SARIF + HTML artifact.                |
| `dependency-check-scheduled.yml`           | Nightly + dispatch | **Yes** — this is the enforced scan        | Fails on CVSS ≥ 7 (`failBuildOnCVSS`). Owns the shared NVD cache. |
| `dependency-review.yml`                    | PR                 | **Yes** — `fail-on-severity: high`         | Only diffs *newly introduced* deps; uses the GitHub Advisory DB.  |

So a red nightly run blocks nothing and merges keep working — it only shows up as a failed scheduled run, which is easy to miss. That is the main reason to run
this command by hand.

## Why green means nothing here

Three failure modes have all actually occurred in this repo. Check for each before believing a clean result:

1. **`Dependencies Scanned: 0`.** In August 2026 the scan reported zero findings for months because `scanProjects` was configured with project *names* while the
   plugin matches project *paths* — every project was skipped and the CVSS gate passed trivially. **Always read `Dependencies Scanned` out of the HTML report
   first.** If it is 0, the scan is broken: report that as the finding and stop. Do not report "no vulnerabilities found".
2. **A skipped upload that looks like a pass.** Both workflows guard their report uploads with `hashFiles('<path>') != ''`. If the plugin's output path changes
   (it did in Dependency-Check 13.0.0), the guard matches nothing, the step is silently skipped, and the job stays green while findings stop reaching Code
   Scanning. Confirm the upload steps *ran*.
3. **`continue-on-error` disguises a failed step.** For the PR job, the REST API reports a failed-but-continued step's `conclusion` as `success`; only `outcome`
   holds the truth, and it is not exposed in the step listing. Never conclude "the scan passed" from the PR job's step status — use the scheduled run, the job
   summary, or the log.

## Step 1 — Locate and read the latest OWASP report

Prefer the most recent **scheduled** run on `main` (the enforced scan). Fall back to the `build-backend` run of a PR if you are reporting on a branch.

```bash
gh run list --workflow=dependency-check-scheduled.yml --limit 5 \
  --json databaseId,status,conclusion,event,headBranch,createdAt \
  --jq '.[] | "\(.databaseId)  \(.status)/\(.conclusion // "-")  \(.event)  \(.headBranch)  \(.createdAt)"'
```

Download the HTML report and read the headline counts **before** anything else:

```bash
gh run download <RUN_ID> -R enorm-labs/event-junkie -n dependency-check-report -D <SCRATCH>/dcreport
grep -oE "(Dependencies Scanned|Vulnerabilities Found)</i>:&nbsp;[^<]*" <SCRATCH>/dcreport/dependency-check-report.html
```

If the artifact is missing, the scan aborted (usually a cold NVD cache or an NVD outage) or the upload was silently skipped — both are findings worth reporting.

For structured per-finding data, the SARIF that reached Code Scanning is easier to work with than the HTML:

```bash
gh api "repos/enorm-labs/event-junkie/code-scanning/alerts?tool_name=dependency-check&ref=refs/heads/main&state=open&per_page=100" --paginate \
  --jq '.[] | [(.rule.security_severity_level // .rule.severity), .rule.id, (.most_recent_instance.location.path | split("/") | .[-1])] | @tsv'
```

Expect heavy duplication: Dependency-Check attributes a CVE to every artifact matching the CPE, so one netty CVE can appear 25 times. **Report unique CVEs, with
the affected artifact families and an instance count** — never the raw alert total as if it were the number of problems.

A useful cross-check on whether the scan is really working is the analysis history — a long run of `results=0` is the signature of the failure mode above:

```bash
gh api "repos/enorm-labs/event-junkie/code-scanning/analyses?per_page=50" \
  --jq '.[] | select(.category=="owasp-dependency-check") | "\(.created_at)  \(.ref)  dc=\(.tool.version)  results=\(.results_count)"'
```

## Step 2 — Read the GitHub Dependabot alerts

```bash
gh api repos/enorm-labs/event-junkie/dependabot/alerts --paginate \
  --jq '.[] | select(.state=="open") | [.security_advisory.severity, .security_advisory.cve_id // .security_advisory.ghsa_id,
        .dependency.package.name, .dependency.scope, (.security_vulnerability.first_patched_version.identifier // "none")] | @tsv'
```

If this returns `403`, the token lacks the alerts scope — say so and suggest `gh auth refresh -s security_events` rather than reporting "no alerts". An empty
list and an unauthorized request look identical if you only check the output length.

Dependabot reads the dependency graph submitted by `dependency-submission.yml`, so its view is of Gradle's resolved graph on `main` **as of the last
submission**
— not of your working tree. Two consequences worth checking rather than assuming:

- `.dependency.scope` is `null` for every alert here (the Gradle submission does not populate it), so you cannot use it to separate runtime from test/dev
  dependencies. Determine that from the `dependencies` task instead, or from where the artifact is declared.
- Alerts can be **stale in both directions**: an alert may name a version already upgraded on `main`, and a fresh local change is invisible until the next
  submission. Always compare an alert's `first_patched_version` against the *currently resolved* version before reporting it as outstanding.

## Step 3 — Reconcile the two sources

They will disagree, and the disagreement is informative rather than a bug:

- **Different databases.** Dependency-Check matches NVD CPEs; Dependabot uses the GitHub Advisory Database. NVD's CVSS can differ sharply from the CNA's — we
  have a live case where NVD scores a Kotlin CVE 9.8 while JetBrains scores it 6.7.
- **Different scope.** Dependency-Check scans every resolved artifact including test and dev dependencies; Dependabot follows the submitted dependency graph.
- **CPE matching is fuzzy.** Dependency-Check produces genuine false positives by matching an artifact name onto an unrelated product's CPE.

Use the overlap as a confidence signal: **in both → highest confidence and real. Dependabot only → real, Dependency-Check's CPE simply missed it.
Dependency-Check only → verify before believing it.**

**Neither source is a superset of the other, so never report from just one.** This is not theoretical: in August 2026 a high-severity CVE in
`com.ongres.scram` (a transitive of `r2dbc-postgresql`) appeared in Dependabot and was entirely absent from the Dependency-Check findings, while
Dependency-Check independently flagged real CVEs that Dependabot did not raise. Reporting from either source alone would have missed something real.

## Step 4 — Triage each unique finding

For every unique CVE, in this order:

1. **Is it already suppressed?** Check `owasp-suppressions.xml`. A suppressed CVE should not appear at all — if it does, the entry's `packageUrl` scope no
   longer matches and the entry needs revisiting.
2. **Is it already being handled?** Check open PRs and the open issues (`gh issue list --label area:security`, or `grep build/BACKLOG.md`) before reporting it
   as new.
3. **Verify against the authoritative advisory — do not trust the artifact name.** This is the step that separates a useful report from a noisy one:

   ```bash
   gh api "/advisories?cve_id=CVE-YYYY-NNNNN" \
     --jq '.[] | "\(.severity)  pkgs=\([.vulnerabilities[].package.name]|unique|join(","))  vuln=\([.vulnerabilities[].vulnerable_version_range]|unique|join(" | "))  fixed=\([.vulnerabilities[].first_patched_version]|unique|join(","))"'
   ```

   Then compare against the **resolved** version, not the requested one — Gradle prints `1.0 -> 2.0` and a careless grep reads the wrong side:

   ```bash
   ./gradlew -q :events-importer:dependencies --configuration runtimeClasspath | grep -E "<artifact>" | sed 's/^[| +\\-]*//' | sort -u
   ```

   Classify as **real and fixable** / **real with no stable fix** / **false positive** (wrong product, wrong artifact, or version out of range).
4. **Work out how it would be fixed**, because that determines who can act:
    - **Project-managed** (a `*.version` property in `gradle.properties`, or a plugin version in `settings.gradle.kts`) — an ordinary bump.
    - **BOM-managed** (Spring Boot / Spring Modulith) — cannot be bumped as a normal dependency. Overriding means setting the BOM's own property name in
      `gradle.properties`; `io.spring.dependency-management` resolves BOM properties from Gradle project properties, and it reaches every module.
    - **Transitive only, with no BOM entry** — neither of the above applies; it needs a `constraints` block in each module that pulls it in. Check first whether
      upgrading the *direct* dependency carries the fix, and only pin the transitive if it does not.
    - **No stable fix available** — say so plainly; the only options are an accepted-risk suppression or relaxing the gate, both of which are the user's call.

## Step 5 — Check whether existing overrides have become obsolete

Every BOM override and transitive `constraints` pin holds us at a version the BOM did not choose. Once upstream catches up, the override stops protecting
anything and starts doing harm: it silently pins us **behind** the BOM, so a later Spring Boot upgrade that would have raised the dependency has no effect and
the staleness is invisible. Treat these as temporary by default and re-check them on every run.

The overrides live in the "Spring Boot BOM overrides (CVE remediation)" block in `gradle.properties`, plus any `constraints` blocks in the module build scripts.
For each one, compare the pinned version against what the BOM would now supply on its own:

```bash
# What we currently resolve, with the override in place
./gradlew -q :events-importer:dependencies --configuration runtimeClasspath | grep -E "<artifact>" | sed 's/^[| +\\-]*//' | sort -u
# What the BOM alone would supply: comment the property out (or `-P<name>=` it away), then re-run and compare
```

Report an override as **obsolete and safe to delete** when the BOM's own version is greater than or equal to the pinned one, and as **still required**
otherwise — naming the CVE that justifies keeping it. Removing it is an ordinary change; recommend it, but leave the edit to the user unless asked.

## Output

Lead with the **health of the scan itself** — `Dependencies Scanned`, when it last ran, and whether the uploads fired. A report that opens with "no
vulnerabilities" when the scanner examined nothing is worse than no report.

Then a prioritized table of unique findings:

| CVE | Severity (NVD / GH) | Package | Resolved | Fixed in | Sources | Verdict |
|-----|---------------------|---------|----------|----------|---------|---------|

Follow with:

- **Blocking the gate** — findings at CVSS ≥ 7 that will keep the nightly scan red, and what each needs.
- **Real but below the gate** — worth fixing, not urgent.
- **Suggested suppressions** — false positives with the evidence for that verdict. Draft the reasoning; do not write the file.
- **No stable fix** — flag explicitly as an accepted-risk decision for the user.
- **Already known** — matching an existing suppression, open PR, or open issue.
- **Obsolete overrides** — BOM overrides and transitive pins the BOM has caught up with, which should now be deleted. Also flag any suppression whose
  `packageUrl` no longer matches anything, since it is dead weight hiding nothing.

State plainly what you verified versus what you inferred. If you could not run a scan and are reading a report from CI, say when it was produced and against
which commit — a stale report describing an old dependency set is a common way to reach a confidently wrong conclusion.
