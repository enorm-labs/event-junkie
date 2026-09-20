# OWASP Top 10 Review

Walk the [OWASP Top 10:2025](https://top10.owasp.org/2025) against this repository as deployed, and write a report. **Read-only, and it files nothing**: the
tree, the workflows, the ADRs, the threat model and read-only API calls are the input; a person decides what becomes an issue. `dast.yml` covers A02, A05 and
half of A01, A04 and A10 by sending requests; this reads the design half — supply chain, integrity, alerting, exceptional conditions — and says per category
what ZAP already covers, so the report carries the gap, not a second alert list.

## Important

- **Diff-first.** Categories move when a boundary changes, not when a week passes: a category whose evidence did not move gets one line,
  `unchanged since <last commit date>`; one whose evidence moved gets the review. A quiet week is a short report.
- **Evidence is a path, a command and its output.** `admin API unroutable` with nothing behind it is an assertion; show the `grep`, the `helm template … | yq`,
  the `gh api` call, and what came back.
- **Never write** — tracker, tree or cluster; nothing here holds a kubeconfig. `THREAT_MODEL.md` rows are read, never edited; a wrong row is a finding.
- **A finding names its threat-model row** (`B1`–`B10`, `Cluster-wide`) or says there is none — a finding with no row is a boundary the model missed (A06).
- **What ZAP covers is out of scope by name.** Check that `.zap/` still says `FAIL` for what the chart guarantees; do not re-derive the headers.
- `git --no-pager`, `gh` non-interactive.

## Arguments

```text
/owasp-top-10 [--since N] [--unattended]
```

- **`--since N`** — the window in days. Default `7`, the weekly cadence. Pass a larger number after a gap, or after an ADR that adds a surface.
- **`--unattended`** — the runner mode; see below.

## Step 1 — The window, and what moved in it

```sh
SINCE="${SINCE:-7}"
git --no-pager log --since="$SINCE days ago" --format='%h %ad %s' --date=short -- \
  .github/workflows deploy .zap docs/adr docs/security/THREAT_MODEL.md docs/ops/SECRETS.md \
  events-bff/src/main/kotlin/de/norm/events/GlobalExceptionHandler.kt \
  events-bff/src/main/kotlin/de/norm/events/ProblemDetailErrorHandler.kt \
  events-bff/src/main/kotlin/de/norm/events/NulByteFilter.kt \
  events-bff/src/main/kotlin/de/norm/events/event/EventSearchRepository.kt \
  events-bff/src/main/kotlin/de/norm/events/image \
  events-importer/src/main/kotlin/de/norm/events/scraper/EventImportService.kt \
  'events-importer/src/main/kotlin/**/*Controller.kt' 'events-bff/src/main/kotlin/**/*Controller.kt'
```

Then, per category below, the last commit that touched its evidence: `git --no-pager log -1 --format=%ad --date=short -- <paths>`. That date is what the
`unchanged since` line carries. Record the head commit (`git rev-parse --short HEAD`) and the site's version (`curl -fsS "$SITE_URL/api/meta"`), because a
finding is only reproducible against the tree that produced it.

## Step 2 — The categories

Each category: the evidence to read, the question to answer, what ZAP covers so it is not repeated, and what a finding looks like. Read the evidence in full
when the window moved it; read the last commit's diff when it did not, and say `unchanged`.

### A01 · Broken Access Control

- **Evidence:** `deploy/charts/event-junkie/templates/ingress.yaml`, `tests/ingress_test.yaml`, `scripts/cluster-assertions.sh`, `templates/networkpolicy.yaml`,
  ADR-023, every `@RequestMapping` under `events-importer/src/main/kotlin` (`grep -rn '@RequestMapping' events-importer/src/main/kotlin`).
- **Question:** is the importer's admin API still unroutable from the internet, and did the window route anything new? Every path the Ingress carries goes
  to the frontend or the BFF, and nothing else. A new importer controller under a path the BFF does not own is not a finding by itself; the finding is a
  path rule that reaches it.
- **ZAP covers:** IDOR on slugs, forced browsing of `/api/admin/**` through Traefik (the full scan's 404 is `tests/ingress_test.yaml`'s assertion, live).
- **Finding shape:** the path rule or the NetworkPolicy allowance that opens what ADR-023 keeps closed, with the rendered object (`helm template … | yq`).
  Threat-model row B2 or B3.

### A02 · Security Misconfiguration

- **Evidence:** `.zap/rules-k3d-full.tsv` and `.zap/rules-production.tsv` against `templates/security-headers-middleware.yaml` and `values.yaml`
  (`ingress.securityHeaders`); the `securityContext` and PSA labels in `values.yaml` and `templates/*-deployment.yaml`; `curl -sI "$SITE_URL/"` for what
  production sends today.
- **Question:** does the rules file say `FAIL` for every header the chart guarantees, and does production send every header the chart says it does? A
  header the chart adds and the rules file leaves at `WARN` is drift in the gate. A header the chart says and `curl -sI` does not show is a release not yet
  cut — say which.
- **ZAP covers:** the headers themselves, the CSP, cookies, error pages, directory listing. Do not list them again.
- **Finding shape:** the rule id and the header, with the `grep` on the rules file and the `curl -sI` line. Row B1.

### A03 · Software Supply Chain Failures

- **Evidence:** `.github/workflows/release.yml` (the `attest-build-provenance` and `cosign sign` steps, and their `if:`), `deploy/clusters/*/oci-repository.yaml`
  (`spec.verify` on each — production is expected to lack it until the first signed release, #1425), `.github/renovate.json5` and `.github/dependabot.yml`
  (ADR-024's boundary), `.trivyignore` (every entry dated), every `uses:` in `.github/workflows/*.yml` a 40-character SHA
  (`grep -rhoE 'uses: [^@]+@[^ ]+' .github/workflows | grep -vE '@[0-9a-f]{40}'` must print nothing), and the required checks on `main`
  (`gh api repos/{owner}/{repo}/rulesets --jq '.[] | select(.name=="main") | .id'`, then the ruleset's `required_status_checks`).
- **Question:** is anything published unsigned, pulled unverified, pinned by tag, or waived without a date? Did the window add a workflow with `contents:
write` or a new App to `merge-gate.yml`'s allow-list?
- **ZAP covers:** nothing.
- **Finding shape:** the file and line, the command that shows it. Rows B6 and B9.

### A04 · Cryptographic Failures

- **Evidence:** `templates/clusterissuer.yaml` and each cluster's `helm-release.yaml` (`certManager`, `tls`), `values.yaml` `ingress.securityHeaders.hsts`,
  `docs/ops/SECRETS.md` (what is encrypted with SOPS/age, what is created by hand, the imgproxy key and salt), `curl -sI "$SITE_URL/" | grep -i strict`.
- **Question:** what is plaintext in transit or at rest, and is any of it a secret? The database's disk on the node, the backups' encryption, the tunnel.
  Claim only what a file says: SECRETS.md and BACKUPS.md are the source, not memory.
- **ZAP covers:** TLS configuration, HSTS presence, mixed content.
- **Finding shape:** the value or the absence, with the file. Rows B3, B7, Cluster-wide.

### A05 · Injection

- **Evidence:** `EventSearchRepository.kt` (the `SORT_COLUMNS` allow-list and every string that reaches `DatabaseClient.sql(`), every `@Query` in both
  modules (`grep -rn '@Query' events-bff/src/main/kotlin events-importer/src/main/kotlin`), every `DatabaseClient.sql(` call, and `NulByteFilter.kt`.
- **Question:** did the window add raw SQL that interpolates a request value instead of binding it? A `$EVENTS_SCHEMA` interpolation is the convention and
  not a finding; a `$q` or `${filter}` inside a SQL string is.
- **ZAP covers:** the fuzzing itself — every parameter of every endpoint, nightly, past the rate limiter.
- **Finding shape:** the file, the line, the interpolated name. Row B3.

### A06 · Insecure Design

- **Evidence:** `docs/security/THREAT_MODEL.md` — its `Last reviewed` date, its `### Open, ranked` list, its `### Accepted` list — against the window's
  commits that touched a boundary: `templates/ingress.yaml`, `templates/networkpolicy.yaml`, `deploy/clusters/*`, a new top-level package under
  `de.norm.events` in any module, a new `agent-*.yml`, a new outbound host in the importer.
- **Question:** did a boundary change without the model? Each open row: is its issue still open, and did anything in the window close or widen it? Each
  accepted row: does its reason still hold?
- **ZAP covers:** nothing.
- **Finding shape:** the commit and the boundary it touched, and which row should carry it — or that none does. Whatever row.

### A07 · Authentication Failures

- **Evidence:** ADR-023, `grep -rn 'spring-security\|spring-boot-starter-security' */build.gradle.kts`, `grep -rn '@PreAuthorize\|/login\|/auth' */src/main`.
- **Question:** is there still no login, and did the window add one? A login that arrives without a rate-limit middleware in front and without the threat
  model's B7 rewritten is the finding.
- **ZAP covers:** nothing today; there is nothing to authenticate against.
- **Finding shape:** the route and the missing control. Row B7.

### A08 · Software or Data Integrity Failures

- **Evidence:** `deploy/clusters/*/oci-repository.yaml` `spec.verify` (the cluster side of A03), `fix-notices-on-bot-prs.yml` and `validate-notices.yml`
  (who regenerates `events-frontend/src/assets/notices.json` and with which token), `merge-gate.yml`'s allow-list, `.github/instructions/ci-cd.instructions.md`
  § _Nothing running in CI can push to `main`_, the threat model's accepted row on an App pushing to an armed branch.
- **Question:** what does CI consume that CI also produces, and who can change it between the two? Did the window add a workflow that writes to the
  repository, and does it open a pull request rather than push?
- **ZAP covers:** nothing.
- **Finding shape:** the workflow, its permission, the token. Rows B6, B9.

### A09 · Security Logging and Alerting Failures

- **Evidence:** `deploy/alerts/gen_alerts.py` (the rule set), `deploy/alerts/README.md`, `docs/ops/OPENOBSERVE.md`, `docs/ops/HEALTHCHECKS.md`, `site-probe.yml`
  and `mail-probe.yml`, ADR-021, #877.
- **Question:** for each of these, which rule fires and who receives it — a 4xx flood, a 429 storm from the rate limiter, a failed import, a Flux
  reconciliation failure, a certificate that did not renew, a `SourceVerified=False`? An event with no rule is a finding; a rule whose channel is a
  dashboard nobody opens is the same finding.
- **ZAP covers:** nothing.
- **Finding shape:** the event, the rule or its absence, the channel. Row B8.

### A10 · Mishandling of Exceptional Conditions

- **Evidence:** `GlobalExceptionHandler.kt`, `ProblemDetailErrorHandler.kt`, `NulByteFilter.kt`, `.zap/rules-k3d-api.tsv` rows `100000` and `100001`,
  `CachedImageGate.kt` and `ImageObjectReader.kt` (what happens when Object Storage is down), `EventImportService.kt` (what a scraper exception does to the
  source's status and the previous rows), `values.yaml` `ingress.rateLimit`.
- **Question:** where does a failure fail open? A 500 whose body carries a message, an image gate that hands out the venue's URL when the cache is
  unreachable, an import that deletes rows because a page answered 200 with nothing on it. Name the code path and what the caller sees.
- **ZAP covers:** the shape of a 4xx and 5xx body, and any 500 on any fuzzed input.
- **Finding shape:** the code path, the input that reaches it, what the caller sees. Rows B2, B4, B10.

## Step 3 — Write the report

Locally, write it to `temp/owasp-<YYYY-MM-DD>.md` and run `scripts/format-markdown.sh` on it. Unattended, the final message is the report (below), and the
formatter is not run: the runner carries no `node_modules`.

## Running unattended

[`agent-owasp.yml`](../workflows/agent-owasp.yml) invokes this prompt as `/owasp-top-10 --since 7 --unattended` weekly, and the report lands in the job summary,
the `agent-report` artifact, and as a comment on the month's `OWASP Top 10 — weekly reports` issue, which a second job posts after this one ends (#1499).
The same contract as `/plausibility-check`:

- **No `--dry-run`, because every run is one.** The prompt writes nothing anywhere. `gh api` is read-only and goes through `ACTIONS_GITHUB_TOKEN`, the
  Actions token, which is the one that can read rulesets; the App token the shell also holds must not be used for anything but reading the tree.
- **It files nothing.** A category-level finding is a reading of a design, and a reading can be confidently wrong. One person reads one report a week and
  files what is real with [`/new-issue`](new-issue.prompt.md). Drafts go in the report.
- **Your final message is the report, and there is no second turn.** The run ends the moment you stop calling tools. Finish the reading, then write the
  Output section below as your last message.
- **Every claim carries the command that produced it**, and a category line that says `unchanged` carries the date and the command that showed no commit.

## Output

```markdown
# OWASP Top 10:2025 review — <YYYY-MM-DD>, window <N> days

Tree `<head short>`, site `<version>` (`<commitShort>`), <count> commits touched the evidence in the window.

## The ten, one line each

| Category | Status | Line |
| --- | --- | --- |
| A01 Broken Access Control | unchanged since <date> / covered by <file> / accepted: <reason> / open: <finding> | <one sentence, with the row> |
| … | | |

<Every category has a row. Silence is not a pass: a category with nothing to say gets `unchanged since <date>` and the command that showed it.>

## What moved in the window
<The commits from Step 1, each with the category it touched and whether the review found anything.>

## Findings
<Per finding: category, threat-model row or "no row", the evidence (file:line, command, output), what a fix looks like, and a draft in the
 shape of the 🐛 or 🛠 issue form. NEW, or KNOWN with the issue number.>

## What ZAP covers and this run did not re-check
<The rule ids per category, so the reader knows where the other half of the list is asserted.>

## What this run could not read
<A `gh api` that answered 403, a file that does not exist any more, a claim that needs a cluster.>
```

## Notes

- `/security-report` and `/security-triage` reconcile dependency alert feeds nightly; this reads design weekly. Different evidence, different reader.
- The threat model is the map; this is the walk. A finding with no row is the strongest kind. The model's `Last reviewed` date moves only after a full read
  by a person.
- `unchanged` for months is a stable boundary. The line to worry about is `open:` on the same finding three weeks running with no issue number.
