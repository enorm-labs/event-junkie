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
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-staging.yaml
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
deploy/scripts/render-assertions.sh
shellcheck -x deploy/scripts/*.sh
```

`render-assertions.sh` renders the chart once per values file and asserts on the output, so it covers the `helm template` half as well. Add `kubeconform` if it
is installed — CI always runs it:

```bash
helm template t deploy/charts/event-junkie --values deploy/charts/event-junkie/values-staging.yaml \
  | kubeconform -strict -summary -schema-location default \
      -schema-location 'https://raw.githubusercontent.com/datreeio/CRDs-catalog/main/{{.Group}}/{{.ResourceKind}}_{{.ResourceAPIVersion}}.json' -
```

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
   Infra:    fmt ✓  validate ×3 ✓  shellcheck ✓        (omit this line when the diff does not touch infra/)
   Chart:    lint ×3 ✓  assertions ✓  shellcheck ✓     (omit this line when the diff does not touch deploy/)
   ```

   On failure, replace the ✓ with ✗ for the failing step, list the others as skipped if you stopped early, and quote the first useful error line below the
   summary.

## Gotchas

- **Java 25 required** — if `./gradlew` fails with an unsupported class file version, run `sdk env` to pick up the pinned JDK from `.sdkmanrc`.
- **Database isn't required** for this skill — the build uses Testcontainers for tests; the dev `compose.yaml` Postgres is only needed for `bootRun`.
- **NVD_API_KEY** is *not* needed here — `dependencyCheckAggregate` is not part of `build`.
- **Playwright browser missing** — the first `test:e2e` run needs the chromium binary. If it fails with
  `Executable doesn't exist`, run `npx playwright install chromium` once (from `events-frontend/`) and re-run.
- **Scoping by diff**: if the diff touches only `events-frontend/`, skip the backend sequence; if it touches only backend modules, skip the frontend sequence;
  run the infrastructure sequence only when it touches `infra/`, and the chart sequence only when it touches `deploy/`. Use
  `git --no-pager diff --name-only main..HEAD` (or against the merge-base) to decide.
- **`validate` does not render `templatefile`.** A change to `infra/modules/environment/cloud-init/` can pass every check above and still produce cloud-init
  that a booting server rejects. Say so in the report rather than implying the cloud-init is verified.
- **`tofu` or `shellcheck` missing** — `brew install opentofu shellcheck`. Do not report the infra sequence as passed when it was skipped for a missing tool.
- **`helm`, `yq` or `kubeconform` missing** — `brew install helm yq kubeconform`. Same rule: a skipped chart sequence is not a passed one.
- **The chart gate proves nothing about a running cluster.** It is a syntax and shape gate: the chart has never been installed anywhere, and the images it
  references do not exist yet. Report it as "renders and passes assertions", never as "the deployment works".
