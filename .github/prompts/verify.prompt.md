# Verify

Run the full pre-PR verification sequence — backend, frontend and infrastructure — then report what passed, what failed, and the first actionable failure.

## What it runs

### Backend (from repo root)

```bash
./gradlew ktlintCheck detekt build koverLog
```

Gradle's `build` task already depends on `check` (which runs tests), so this single invocation covers compile, ktlint, detekt, tests, and Kover thresholds.
`koverLog` then prints the coverage summary to stdout so you can see it without opening the HTML report.

### Frontend (from `events-frontend/`)

```bash
cd events-frontend
npm run type-check
npm run lint
npm run test:unit -- --run
npm run test:e2e -- --project=chromium
```

- `type-check` — `vue-tsc --build`
- `lint` — runs both `oxlint --fix` and `eslint --fix --cache` (via `run-s lint:*`)
- `test:unit -- --run` — vitest in single-run mode (default is watch)
- `test:e2e -- --project=chromium` — Playwright e2e on chromium only. The suite mocks the BFF with request routing and Playwright auto-starts the Vite dev
  server (`webServer` in `playwright.config.ts`), so **no backend or database is required**. Chromium-only keeps the pre-PR gate fast; CI (`build-frontend.yml`)
  runs the full browser + mobile matrix.

### Markdown (from repo root, only when the diff touches any `.md` file)

```bash
scripts/format-markdown.sh check
```

Writes nothing; exits 1 listing every file that is not formatted. The fix is `scripts/format-markdown.sh` with no argument — it rewrites in place, so this is
never a "go and work out what is wrong" failure. The `pre-commit` hook runs the same script and `validate-docs.yml` runs this exact command in CI, so a clean
local commit history means both pass; this step is here for the case where hooks were skipped with `--no-verify`. Do not reach for `oxfmt` directly — the script
pins the version and the scope, both of which matter (AGENTS.md §Code Conventions).

### Comment volume (from repo root, always)

```bash
scripts/comment-density.sh check
scripts/comment-lint.sh check
```

Exits 1 naming any area that carries more comment lines than `scripts/comment-baseline.txt` allows, and the ratchet only turns one way: the fix is to compress
or delete, not to raise the number. An area that has dropped below its baseline is reported too and passes — regenerate with `scripts/comment-density.sh
update-baseline` and commit the lower figure in the same PR. `comment-lint.sh` is the same ratchet over the rules detekt and ESLint cannot see — the block
cap, file density, and markdown headings, date literals or change-narration inside a comment in `.tf`, `.sh`, `.yaml` and `.py`. Both take under a second
and reach no network, and `validate-comments.yml` runs them on every pull request. See #713.

### Infrastructure (from repo root, only when the diff touches `infra/`)

```bash
tofu fmt -recursive -check -diff infra
tofu -chdir=infra/bootstrap                init -backend=false && tofu -chdir=infra/bootstrap                validate
tofu -chdir=infra/environments/production  init -backend=false && tofu -chdir=infra/environments/production  validate
tofu -chdir=infra/environments/staging     init -backend=false && tofu -chdir=infra/environments/staging     validate
shellcheck -x infra/modules/environment/cloud-init/*.sh
```

**Never run `tofu plan` or `tofu apply`** — they need a Hetzner API token and spend money. See [infra/AGENTS.md](../../infra/AGENTS.md).

Run all three stacks even for a one-line change: they share `modules/environment`, so an edit there can break one and leave the others green.

### Helm chart (from repo root, only when the diff touches `deploy/`)

```bash
helm lint --strict deploy/charts/event-junkie --set database.host=10.0.1.2 --set database.existingSecret=events-db
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
helm unittest --strict deploy/charts/event-junkie
scripts/cluster-assertions.sh
```

`helm unittest` needs the plugin, which is not installed by default:

```bash
helm plugin install https://github.com/helm-unittest/helm-unittest --version v1.1.2 --verify=false
```

`--verify=false` is a Helm 4 requirement — the local binary is v4 and refuses an unverifiable plugin source without it. CI pins Helm 3 and does not need the
flag. Pin the same version CI pins (`HELM_UNITTEST_VERSION` in `.github/workflows/validate-chart.yml`).

Also when the diff touches `scripts/version.sh`, `gradle.properties` or either `Chart.yaml` version field:

```bash
scripts/version.sh check        # the four files agree
scripts/version-test.sh         # snapshot versions still ORDER
```

`version-test.sh` is the ordering gate, and it is a different question from `check` (#455). It resolves fabricated version sets through Helm's own Masterminds
constraint solver and asserts the newest wins — because a snapshot version that parses, lints and publishes can still leave staging pinned to last week's chart,
silently, which is exactly what happened.

`helm unittest` covers the two value sets the chart can be rendered from on its own; `scripts/cluster-assertions.sh` extracts `spec.values` from **each
cluster's `HelmRelease`** and re-runs the invariant suites against it, so the assertions gate what Flux will actually deploy rather than a file nothing deploys.
It also carries the assertions that read _relationships between files_ — image tags, `dependsOn`, third-party version pins — which no single render can see.
Between them they cover the `helm template` half as well, so there is no separate render step here.

There is no `shellcheck deploy/scripts/*.sh` line any more — #430 ported the render assertions to helm-unittest suites and `deploy/scripts/` no longer exists.
`scripts/cluster-assertions.sh` took over what it could not express, and it is linted with the rest of `scripts/`.

Add the schema gate if `flux` is installed — CI always runs it. It replaced `kubeconform` in #414, because it also evaluates CEL rules with the API server's own
semantics, catches duplicate YAML keys, and reads SOPS-encrypted fields without decrypting them:

```bash
helm template t deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml \
  | flux schema validate - -s ecosystem --verbose
flux schema validate deploy/clusters -s ecosystem --verbose \
  --skip-kind kustomize.config.k8s.io/v1beta1/Kustomization
```

**Watch the `Skipped:` count, not just `Invalid:`.** A resource whose schema is missing is skipped, not failed — so a green summary with a non-zero skip count
means something went unchecked.

**Never run `helm install`, `upgrade`, `uninstall` or `rollback`** — they reach a real cluster. Note that `helm install --dry-run` does too, for capability
discovery; `helm template` and `--dry-run=client` do not. See [deploy/AGENTS.md](../../deploy/AGENTS.md).

The base `values.yaml` cannot render without the two `--set` flags above, and that is deliberate: `database.host` and `database.existingSecret` have no safe
default.

## How to run the skill

1. Run the **backend** sequence first. If it fails, surface the first failing task, quote the actual error lines from Gradle's output, and stop — don't run the
   frontend until backend is green (the user usually wants to fix one stack at a time).
2. If backend passes, run the **frontend** sequence next, then the **infrastructure** sequence if the diff touches `infra/`, then the **Helm chart** sequence if
   it touches `deploy/`.
3. If `ktlintCheck` fails, suggest `./gradlew ktlintFormat` to auto-fix before re-running — per AGENTS.md, ktlint auto-format should be tried first.
4. If `lint` fails on the frontend, note that both oxlint and eslint already pass `--fix`, so remaining failures are genuine issues that need manual edits.
5. Report at the end with a compact summary:

    ```
    Backend:  ktlintCheck ✓  detekt ✓  build ✓  koverLog ✓
    Frontend: type-check ✓  lint ✓  test:unit ✓  e2e ✓
    Markdown: format ✓                                  (omit this line when the diff touches no .md file)
    Infra:    fmt ✓  validate ×3 ✓  shellcheck ✓        (omit this line when the diff does not touch infra/)
    Chart:    lint ×3 ✓  assertions ✓  shellcheck ✓     (omit this line when the diff does not touch deploy/)
    ```

    On failure, replace the ✓ with ✗ for the failing step, list the others as skipped if you stopped early, and quote the first useful error line below the
    summary.

## Gotchas

- **Java 25 required** — if `./gradlew` fails with an unsupported class file version, run `sdk env` to pick up the pinned JDK from `.sdkmanrc`.
- **Database isn't required** for this skill — the build uses Testcontainers for tests; the dev `compose.yaml` Postgres is only needed for `bootRun`.
- **NVD_API_KEY** is _not_ needed here — `dependencyCheckAggregate` is not part of `build`.
- **Playwright browser missing** — the first `test:e2e` run needs the chromium binary. If it fails with
  `Executable doesn't exist`, run `npx playwright install chromium` once (from `events-frontend/`) and re-run.
- **Scoping by diff**: if the diff touches only `events-frontend/`, skip the backend sequence; if it touches only backend modules, skip the frontend sequence;
  run the infrastructure sequence only when it touches `infra/`, and the chart sequence only when it touches `deploy/`. Use
  `git --no-pager diff --name-only main..HEAD` (or against the merge-base) to decide.
- **`validate` does not render `templatefile`.** A change to `infra/modules/environment/cloud-init/` can pass every check above and still produce cloud-init
  that a booting server rejects. Say so in the report rather than implying the cloud-init is verified.
- **`tofu` or `shellcheck` missing** — `brew install opentofu shellcheck`. Do not report the infra sequence as passed when it was skipped for a missing tool.
- **`helm`, `yq` or `flux` missing** — `brew install helm yq fluxcd/tap/flux`, then `flux plugin install schema`. Same rule: a skipped chart sequence is not a
  passed one.
- **The chart gate proves nothing about a running cluster.** It is a syntax and shape gate. The runtime counterpart is
  [`/k3d-rehearsal`](k3d-rehearsal.prompt.md) — `all` for the working tree, `flux-all` for the published artifacts through Flux. Report this sequence as
  "renders and passes assertions", never as "the deployment works".
