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
scripts/ste-lint.sh check
```

Writes nothing; exits 1 listing every file that is not formatted. The fix is `scripts/format-markdown.sh` with no argument — it rewrites in place, so this is
never a "go and work out what is wrong" failure. The `pre-commit` hook runs the same script and `validate-docs.yml` runs this exact command in CI, so a clean
local commit history means both pass; this step is here for the case where hooks were skipped with `--no-verify`. Do not reach for `oxfmt` directly — the script
pins the version and the scope, both of which matter (.github/instructions/markdown.instructions.md).

`ste-lint.sh` holds `docs/**` to a per-area ceiling that only moves down, over what the documents _say_ rather than how much they say. It counts the structural ASD-STE100 findings — a sentence over 25 words
(20 inside a numbered step), a semicolon, present perfect, a file over 150 lines with no `## The short version`, an `Amendment,` heading — against
`scripts/ste-baseline.txt`, one number per rewrite phase. `scripts/ste-lint.sh report --top 20` shows where they are and `stats` gives the share of sentences
over the cap. A sentence that has to stay long takes `<!-- ste-lint: allow <reason> -->` on the line above it, and the reason is not optional. Only the
structural half of the standard is checkable here — the lexical rules need a dictionary this repository is not licensed to carry — so nothing it prints means a
document is STE-compliant. `validate-docs.yml` runs it in CI. See #733 and
[.github/instructions/documentation.instructions.md](../instructions/documentation.instructions.md).

### Comment rules (from repo root, always)

```bash
scripts/comment-lint.sh check
scripts/skill-parity.sh
scripts/rules-parity.sh
scripts/collector-parity.sh
```

Exits 1 on **any** violation of the rules detekt and ESLint cannot see — the block cap, file density, and markdown headings, date literals or
change-narration inside a comment in `.tf`, `.sh`, `.yaml` and `.py`. There is no baseline to absorb one: the fix is to compress the comment, delete it, or say
why it stays with `# comment-lint: allow <reason>`. It takes under a second, reaches no network, and `validate-comments.yml` runs it on every pull request.
See #713.

**The volume ceiling is gone.** `scripts/comment-density.sh` still measures — `report --top 20` is how `/compact-comments` and `/code-review` find the files
worth opening — but it gates nothing and has no baseline. An area budget failed a build for the _count_ of comment lines rather than for a comment anybody
would object to, which priced deleting a stale paragraph the same as adding a load-bearing one.

`skill-parity.sh` is a third check riding along here because it is the same shape and the same cost: `.claude/skills/` and `.claude/commands/` are
parallel trees of `@` pointers with nothing joining them, so a skill added to one and not the other is silently absent from the other. It also asserts
every pointer resolves and every skill is listed in `CLAUDE.md`. `validate-docs.yml` runs it in CI.

`rules-parity.sh` is the same shape one directory over. A path-scoped rule lives once in `.github/instructions/`, carrying an `applyTo` string for Copilot and
a `paths` list for Claude Code; each agent reads only its own key, so neither can notice the two describing different globs. It also asserts every
`.claude/rules/` symlink resolves, that no rule body is an `@` pointer — which is expanded at launch whatever `paths` says, defeating the scoping silently —
and that every rule is linked from the AGENTS.md table. `validate-docs.yml` runs it beside `skill-parity.sh`.

`collector-parity.sh` is the third of the same shape, over a fact written in four places: the importer's `LogFields` and `LogContext`, the BFF's
`LogContextConfiguration`, the collector's OTTL allowlist in `deploy/clusters/base/collector.yaml`, and the table in `docs/ops/PLATFORM_SETUP.md` §7. Two
constant objects rather than one is deliberate — a shared package in `events-core` becomes a Spring Modulith module of its own (#945) — so a check is the
substitute for sharing them. **The failure it prevents is an empty query result, not an error**: a name missing from the allowlist produces no column, and
OpenObserve answers "no rows" rather than "no such column". It also asserts that the paths and `Log*` symbols those four files cite still exist, which is the
half with a history: #982 and #953 each left pointers at something that had been renamed or deleted, and both were caught by eye.

**It also matches every glob against the index**, via `git ls-files -- ':(glob)…'`, because a glob that hits nothing is a rule that loads for no file while
every other check passes. `deploy/**/*.yml` shipped in a first draft of the kubernetes rule and matched none of the 70 files there, all of which are `.yaml`.

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

### The open-source notices (when the diff touches either ecosystem's dependency declarations)

```bash
scripts/notices-parity.sh check
```

`events-frontend/src/assets/notices.json` is generated, committed, and rendered at `/legal/notices`, and until #1037 nothing joined it to its inputs. It
drifted by 51 components before a pull request happened to regenerate it. **A stale notices file understates what we distribute**, which is the direction that
matters — see [docs/LEGAL.md](../../docs/LEGAL.md) §9.2.

This one is **not** in the always-run block above, unlike the three parity checks: it resolves both dependency graphs and reaches the network, so it costs
seconds rather than milliseconds. Run it when `package.json`, `package-lock.json`, `gradle.properties` or any `build.gradle.kts` moved. `check` restores the
committed file before exiting whatever happens; the bare form regenerates it for committing. `validate-notices.yml` runs it in CI on the same paths.

### The Content-Security-Policy (when the diff touches `deploy/`, `events-frontend/index.html` or `events-frontend/scripts/csp.ts`)

```bash
scripts/csp-parity.sh
```

The policy is written twice — the chart sends the header, and `events-frontend/scripts/csp.ts` applies the same one to `npm run preview` so the Playwright
suite runs against it. The script compares the two lists and recomputes the `script-src` hash from the inline theme script in `index.html`. Editing that
script without the policy blocks it, and the only symptom is a light-mode flash on every load (#846).

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
