# Verify

Run the full pre-PR verification sequence — backend, frontend, documents, infrastructure, chart — then report what passed, what failed, and the first
actionable failure. **This file is the check list**; a subset chosen by hand has cost a CI cycle before.

## What it runs

### Backend (from repo root)

```bash
./gradlew ktlintCheck detekt detektMain detektTest build koverLog -PwarningsAsErrors
```

`build` depends on `check`, so this covers compile, ktlint, all three detekt analyses, tests and the Kover floors; `koverLog` prints the coverage. **All three
detekt tasks**: `detekt` alone skips every type-resolution rule (#407). **`-PwarningsAsErrors`** matches `build-backend.yml`, which fails on a Kotlin warning
where a plain local build does not.

### Frontend (from `events-frontend/`)

```bash
npm run type-check
npm run lint                          # oxlint --fix, then eslint --fix --cache; what remains is a real finding
npm run test:unit -- --run
npm run test:e2e -- --project=chromium
```

The e2e suite mocks the BFF and Playwright starts the Vite server itself, so no backend or database is needed. Chromium only keeps the gate fast; CI runs
the full matrix. **In a worktree, port 5173 may be the other checkout's dev server** — `CI=1` builds and serves `dist/` instead, so build first.

### Always (from repo root)

```bash
scripts/comment-lint.sh check         # any comment-lint violation in .tf, .sh, .yaml, .py — no baseline
scripts/skill-parity.sh               # skills ↔ commands ↔ the AGENTS.md list, every pointer resolves
scripts/rules-parity.sh               # applyTo ↔ paths per rule, symlinks resolve, no @ pointer bodies, every glob hits a file
scripts/collector-parity.sh           # LogFields ↔ LogContextConfiguration ↔ collector.yaml ↔ PLATFORM_SETUP §7
scripts/scope-parity.sh               # the feat scope list, nine copies, labeller's is canonical
scripts/secrets-parity.sh             # the secret count SECRETS.md states ↔ the rows its table lists
scripts/index-parity.sh               # scripts/README.md ↔ scripts/, every referenced script exists and answers --help
```

Each takes under a second and reaches no network; `--help` on any of them says what it checks and why. `scripts/comment-density.sh` measures and gates nothing.

### Markdown (only when the diff touches any `.md` file)

```bash
scripts/format-markdown.sh check      # fix: scripts/format-markdown.sh with no argument
scripts/ste-lint.sh check             # docs/** against scripts/ste-baseline.txt; report --top 20 to locate; <!-- ste-lint: allow <reason> --> to keep
```

Do not reach for `oxfmt` directly — the script pins the version and the scope. **`ste-lint` sees tracked files only**: `git add` a new document first, or it
passes locally and fails CI. `validate-docs.yml` runs both.

### Infrastructure (only when the diff touches `infra/`)

```bash
tofu fmt -recursive -check -diff infra
export TF_DATA_DIR="$(mktemp -d)"   # required locally: a used .terraform sends init to the state bucket, InvalidAccessKeyId
for s in bootstrap environments/production environments/staging; do tofu -chdir=infra/$s init -backend=false && tofu -chdir=infra/$s validate; done
unset TF_DATA_DIR
shellcheck -x infra/modules/environment/cloud-init/*.sh
python3 infra/check_user_data.py    # validate does not render templatefile; this does, and measures the 32 KiB cap
```

All three stacks even for a one-line change — they share `modules/environment`. **Never `tofu plan` or `apply`** ([infra/AGENTS.md](../../infra/AGENTS.md)).

### Helm chart (only when the diff touches `deploy/`)

```bash
helm lint --strict deploy/charts/event-junkie --set database.host=10.0.1.2 --set database.existingSecret=events-db
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
helm unittest --strict deploy/charts/event-junkie
scripts/cluster-assertions.sh
scripts/uid-consistency.sh
helm template t deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml | flux schema validate - -s ecosystem --verbose
flux schema validate deploy/clusters -s ecosystem --verbose --skip-kind kustomize.config.k8s.io/v1beta1/Kustomization
```

`helm unittest` needs the plugin: `helm plugin install https://github.com/helm-unittest/helm-unittest --version <HELM_UNITTEST_VERSION from validate-chart.yml> --verify=false`
(Helm 4 refuses an unverifiable plugin source without the flag). `cluster-assertions.sh` re-runs the invariant suites against each cluster's `spec.values`,
so the gate covers what Flux deploys. **Watch `Skipped:`, not just `Invalid:`** — a resource with no schema is skipped, not failed. **Never `helm install`,
`upgrade`, `uninstall`, `rollback` or `install --dry-run`** ([deploy/AGENTS.md](../../deploy/AGENTS.md)).

Also when the diff touches `scripts/version.sh`: `scripts/version-deserved-test.sh` (the rule, and what `compute` names a commit, against fabricated
histories) and `scripts/version-test.sh` (snapshot versions still order — a version that lints and publishes can still leave staging on last week's chart, #455).

### Conditional, by path

| When the diff touches                                                            | Run                                                                                                                                     |
| -------------------------------------------------------------------------------- | --------------------------------------------------------------------------------------------------------------------------------------- |
| `package.json`, `package-lock.json`, `gradle.properties`, any `build.gradle.kts` | `scripts/notices-parity.sh check` — reaches the network; `check` restores the committed file                                            |
| `docs/LINKS.md`, `docs/ops/DAILY_COMMANDS.md`, `docs/ops/dashboard/`             | `scripts/dashboard-parity.sh check` — needs `events-frontend/node_modules`                                                              |
| any `.py`, `ruff.toml`                                                           | `RUFF="ruff@$(sed -n 's/^  RUFF_VERSION: //p' .github/workflows/validate-python.yml)"; uvx "$RUFF" check && uvx "$RUFF" format --check` |
| `deploy/alerts/`, `deploy/dashboards/`                                           | also `deploy/alerts/test_diff_alerts.py` and `deploy/dashboards/test_lint_dashboard.py`                                                 |
| `deploy/`, `events-frontend/index.html`, `events-frontend/scripts/csp.ts`        | `scripts/csp-parity.sh` — the policy is written twice, and the `script-src` hash follows the inline theme script (#846)                 |

## How to run the skill

1. Scope by diff: `git --no-pager diff --name-only origin/main...HEAD`. Frontend-only skips the backend; backend-only skips the frontend; the always block
   always runs.
2. Backend first. On failure quote the first failing task and Gradle's actual error lines, then stop; on `ktlintCheck` suggest `./gradlew ktlintFormat` first.
3. Then frontend, then each conditional section the diff touches.
4. Report:

    ```
    Backend:  ktlintCheck ✓  detekt ×3 ✓  build ✓  koverLog ✓
    Frontend: type-check ✓  lint ✓  test:unit ✓  e2e ✓
    Always:   comment-lint ✓  skill ✓  rules ✓  collector ✓  scope ✓  index ✓
    Markdown: format ✓  ste-lint ✓                        (omit when no .md moved)
    Infra:    fmt ✓  validate ×3 ✓  shellcheck ✓  user_data ✓   (omit when infra/ untouched)
    Chart:    lint ×2 ✓  unittest ✓  assertions ✓  uid ✓  schema ✓   (omit when deploy/ untouched)
    ```

    On failure, ✗ on the failing step, the rest skipped, the first useful error line below.

## Gotchas

- **Java 25**: `sdk env` picks up `.sdkmanrc`. No database needed (Testcontainers). No `NVD_API_KEY` needed (`dependencyCheckAggregate` is not in `build`).
- **`Executable doesn't exist`** on the first e2e run: `npx playwright install chromium` from `events-frontend/`.
- **Two Gradle daemons clobber `build/classes`**: a full test run while `dev-env.sh` is up fails with `NoClassDefFoundError` in untouched packages.
  `./gradlew --stop`, then build from clean.
- **A missing tool is a skipped check, never a passed one**: `brew install opentofu shellcheck helm yq fluxcd/tap/flux`, `flux plugin install schema`.
- **The chart gate proves nothing about a running cluster.** Report it as "renders and passes assertions"; [`/k3d-rehearsal`](k3d-rehearsal.prompt.md) is the
  runtime counterpart.
