# Development

Everything needed to build, run and check this project locally. The [README](../README.md) gets you running in four commands; this document is the rest of it.

Frontend-specific development lives in [events-frontend/README.md](../events-frontend/README.md). The conventions every change is held to are
in [AGENTS.md](../AGENTS.md) — written for AI agents, but it is simply this project's conventions written down, and it is more complete than any other document
here.

## Contents

- [Prerequisites](#prerequisites)
- [Git hooks (pre-commit)](#git-hooks-pre-commit)
- [Build and run](#build-and-run)
- [The local database](#the-local-database)
- [The `local` profile — logging to a file](#the-local-profile--logging-to-a-file)
- [Running the stack with `dev-env.sh`](#running-the-stack-with-dev-envsh)
- [Calling the APIs](#calling-the-apis)
- [Quality checks](#quality-checks)
- [Infrastructure (OpenTofu)](#infrastructure-opentofu)
- [Container images](#container-images)
- [Running the whole stack on k3d](#running-the-whole-stack-on-k3d)
- [Helm chart](#helm-chart)
- [Performance tests](#performance-tests)
- [Dependencies](#dependencies)

## Prerequisites

| Tool         | Version                                   | Notes                                                             |
|--------------|-------------------------------------------|-------------------------------------------------------------------|
| JDK          | see [`.sdkmanrc`](../.sdkmanrc)           | `sdk env` picks it up; install [SDKMAN](https://sdkman.io/) first |
| Docker       | any recent                                | Postgres is started for you by `bootRun`                          |
| Node.js      | see [`.nvmrc`](../events-frontend/.nvmrc) | frontend only; `nvm use`                                          |
| `pre-commit` | any                                       | for the commit hooks — `brew install pre-commit`                  |

Optional, for specific jobs: [`ijhttp`](https://www.jetbrains.com/help/idea/http-client-cli.html) (running `.http`
files from the CLI), [`k6`](https://k6.io) (performance tests), and [`tofu`](https://opentofu.org/) with `shellcheck` (anything under `infra/` — the
pre-commit hooks below need both).

```bash
sdk env      # Java version from .sdkmanrc
```

## Git hooks (pre-commit)

[Gitleaks](https://github.com/gitleaks/gitleaks) runs as a pre-commit hook via [pre-commit](https://pre-commit.com/)
to keep secrets out of the history. **Install it before your first commit** — it is far cheaper than rewriting history afterwards.

```bash
brew install pre-commit   # macOS
pre-commit install        # installs the hook into .git/hooks
```

It then runs on every `git commit`. To scan without committing:

```bash
pre-commit run gitleaks --all-files       # everything tracked by git
gitleaks detect --source . --verbose      # the entire history (needs: brew install gitleaks)
```

Three more hooks run alongside it, all `local` — they use the `tofu`, `shellcheck` and `helm` already on your machine rather than pulling third-party hook
repositories that would each need their own pinning:

| Hook | Runs on |
|---|---|
| `tofu-fmt` | any `.tf` / `.tfvars` file. It rewrites in place, so a failure means "re-stage and commit again", not "go and fix something" |
| `shellcheck-scripts` | `infra/modules/environment/cloud-init/*.sh` and `deploy/scripts/*.sh` |
| `helm-lint` | anything under `deploy/charts/`. Lints the chart directory, so it takes no filenames |

All three are also CI's job (`validate-infra.yml`, `validate-chart.yml`); the hooks just move the deterministic half of that feedback before the push. If you do
not have the tools installed, the hooks fail — install them (`brew install opentofu shellcheck helm`) or skip with `git commit --no-verify` on a change that
touches none of those paths.

**Do not add a `check-yaml` hook.** Helm templates are Go templates that happen to look like YAML, and a generic parser rejects every one of them. The
`.pre-commit-config.yaml` comment says so next to the hook, because this is the kind of thing that gets "fixed" by adding a standard hook set.

The hooks live in the shared `.git` directory, so they are already active in every worktree — no second
`pre-commit install`.

## Build and run

```bash
./gradlew clean build                 # compile, test, ktlint, detekt, coverage
./gradlew bootRun                     # run both Boot apps
./gradlew :events-importer:bootRun    # or one at a time
./gradlew :events-bff:bootRun
```

`bootRun` also starts the services in [`compose.yaml`](../compose.yaml) — currently just PostgreSQL — via Spring
Boot's [Docker Compose support](https://docs.spring.io/spring-boot/reference/features/dev-services.html#features.dev-services.docker-compose). IntelliJ run
configurations work the same way.

Ports: importer `8081`, BFF `8080`, frontend `5173`, Postgres `56298`.

## The local database

PostgreSQL is exposed on host port **56298** (mapped from the container's 5432). Spring Boot discovers the port itself; you need it only to connect by hand —
`localhost:56298`, credentials `admin` / `admin`, database
`event_junkie`.

If that port is taken:

```bash
POSTGRES_HOST_PORT=5555 ./gradlew :events-importer:bootRun
```

Data lives on the **named volume** `postgres-data`, so it survives the container being stopped and recreated. That is deliberate: re-seeding means re-scraping ~
86 sources, which is not something to do casually ([ADR-007](adr/ADR-007_WEB_SCRAPING_STRATEGY.md) on politeness). To reset the database on purpose:

```bash
docker compose down --volumes     # or: scripts/dev-env.sh db-reset
```

The next `bootRun` recreates it and re-runs the Flyway migrations. Note that an **empty** database makes
`dev-env.sh diff-snapshot` report every existing source as `GONE`, which looks alarming and is not.

## The `local` profile — logging to a file

Both services define a `local` Spring profile whose only effect is to mirror console output to a file, so an import or request run can be grepped afterwards
instead of scrolled in the IDE console.

| Service           | Log file                                     |
|-------------------|----------------------------------------------|
| `events-importer` | `events-importer/build/dev-env/importer.log` |
| `events-bff`      | `events-bff/build/dev-env/bff.log`           |

```bash
./gradlew :events-importer:bootRun --args='--spring.profiles.active=local'
```

In IntelliJ, set **Active profiles: `local`** on the run configuration. Paths are relative to each module directory (`bootRun`'s working directory) and land
under `build/`, which is gitignored.

**The profile gate is deliberate.** On a container platform the log belongs on stdout where the platform collects it; a file appender there would write into the
container's in-memory filesystem. `scripts/dev-env.sh` does not need the profile — it redirects each service's stdout to `build/dev-env/<service>.log` itself.

## Running the stack with `dev-env.sh`

[`scripts/dev-env.sh`](../scripts/dev-env.sh) starts and stops the local stack without remembering docker, gradle and npm incantations. Run it with no arguments
for the full command list.

```bash
scripts/dev-env.sh up all       # importer + bff + frontend, each waited on until it answers
scripts/dev-env.sh status       # database / importer / bff / frontend
scripts/dev-env.sh down all     # add --db to stop Postgres too
```

`up` and `down` take one or more of `importer` (the default) · `bff` · `frontend` · `all`. Each service logs to
`build/dev-env/<service>.log`. The frontend proxies `/api` to the BFF, so starting it alone renders the app but every request 502s.

It also covers the importer workflow: `seed-all`, `seed-one`, `import <slug>`, `snapshot`, `diff-snapshot`,
`check <slug>` and `psql <sql>`.

## Calling the APIs

### Swagger UI

With a service running:

- **events-bff** — <http://localhost:8080/webjars/swagger-ui/index.html>
- **events-importer** — <http://localhost:8081/webjars/swagger-ui/index.html>

The OpenAPI document (JSON) is at `/v3/api-docs` on each port. **The BFF's document is also the source of the frontend's TypeScript types** —
see [events-frontend/README.md](../events-frontend/README.md#regenerate-the-api-types-after-a-bff-change); changing the BFF's public API means regenerating them
in the same PR.

### IntelliJ HTTP Client

[`http/`](../http) holds request files, split by service:

- [`http/importer/`](../http/importer) — the admin CRUD endpoints (venues, artists, promoters, events, sources, dev seed) plus health and OpenAPI checks.
- [`http/bff/`](../http/bff) — the public read API (events, venues, artists, genres) plus health and OpenAPI checks.

The shared `http-client.env.json` sits at the `http/` root (IntelliJ resolves it from parent directories) and defines `importer-host` and `bff-host`.

**From IntelliJ:** start the service, open a `.http` file, select the **local** environment, click ▶. Create requests store their response IDs (e.g.
`{{venue_id}}`), so later update/delete/event requests reference them without copy-paste.

**From the command line**, via the [HTTP Client CLI](https://www.jetbrains.com/help/idea/http-client-cli.html) — no IntelliJ Ultimate licence required:

```bash
brew install ijhttp

./gradlew httpTest      # the full CRUD lifecycle scenario; needs the importer on :8081

cd http
ijhttp --env-file http-client.env.json --env local venues.http
ijhttp --env-file http-client.env.json --env local -L VERBOSE full-lifecycle.http
```

## Quality checks

Everything below runs in CI too; the point of running it locally is not to find out from a red build.

```bash
./gradlew ktlintCheck            # lint
./gradlew ktlintFormat           # auto-fix — run this first when ktlint complains
./gradlew detekt                 # static analysis; HTML at build/reports/detekt/
./gradlew koverLog               # coverage per module, to the console
./gradlew koverHtmlReport        # build/reports/kover/html/index.html
./gradlew koverXmlReport         # for CI tools
```

Detekt's rule customisations live in [`detekt.yml`](../detekt.yml). Kover's exclusions are split across three places and do **not** propagate between them —
see [AGENTS.md](../AGENTS.md) before editing them.

Frontend checks are `npm run type-check`, `npm run lint`, `npm run test:unit`, `npm run test:e2e` and
`npm run test:a11y` — see [events-frontend/README.md](../events-frontend/README.md).

The full pre-PR sequence is a single skill: [`/verify`](../.github/prompts/verify.prompt.md).

### Dependency CVE scanning (OWASP Dependency-Check)

Scans every dependency against the [NVD](https://nvd.nist.gov/). **The build fails on CVSS ≥ 7 (HIGH).**

```bash
./gradlew dependencyCheckAggregate --no-configuration-cache
```

Reports land in `build/reports/`: `dependency-check-report.html` and `.sarif` (the latter uploaded to GitHub Code Scanning). False positives are suppressed in [
`owasp-suppressions.xml`](../owasp-suppressions.xml). The
`--no-configuration-cache` flag is required — the plugin is not configuration-cache compatible.

**Get an NVD API key.** Unauthenticated requests are rate-limited hard enough to make the first database download take 10+ minutes; a free key brings it to
about one.

1. Request one at <https://nvd.nist.gov/developers/request-an-api-key>
2. Locally: `export NVD_API_KEY=your-key-here`
3. In CI: repository secret named `NVD_API_KEY`

## Infrastructure (OpenTofu)

The Hetzner platform is declared in [`infra/`](../infra). **Nothing in it has ever been applied**, and the plan behind it is
[docs/PLATFORM_SETUP.md](./PLATFORM_SETUP.md).

Before changing anything there, read [infra/AGENTS.md](../infra/AGENTS.md) — it opens with the commands that must not be run. These need no credentials and are
exactly what `validate-infra.yml` runs in CI:

```bash
tofu fmt -recursive -check -diff infra
tofu -chdir=infra/bootstrap init -backend=false && tofu -chdir=infra/bootstrap validate
tofu -chdir=infra/environments/production init -backend=false && tofu -chdir=infra/environments/production validate
tofu -chdir=infra/environments/staging  init -backend=false && tofu -chdir=infra/environments/staging  validate
shellcheck -x infra/modules/environment/cloud-init/*.sh
```

Run all three stacks: they share a module, so a change to it can break one and leave the others green.

**`tofu plan` and `tofu apply` are not part of local verification.** They need a Hetzner API token and they spend money — see
[infra/README.md](../infra/README.md) for who runs them and in what order. One consequence worth knowing rather than discovering: `validate` does **not** render
`templatefile`, so a change to the cloud-init template can pass every check here and still produce YAML that a booting server rejects.

## Container images

`events-bff` and `events-importer` each build to a container image. The Dockerfiles are runtime-only — the fat jar is exploded into its layers by Gradle first,
and the **build context is that output directory**, not the module:

```bash
./gradlew :events-bff:bootJarLayers            # → events-bff/build/docker/{dependencies,application,…}
docker buildx build -f events-bff/Dockerfile events-bff/build/docker -t event-junkie/bff:dev --load
```

Both architectures at once — this is what CI does, and it needs no QEMU because neither Dockerfile contains a `RUN`:

```bash
docker buildx build -f events-bff/Dockerfile events-bff/build/docker \
  --platform linux/amd64,linux/arm64 --output type=cacheonly
```

`type=cacheonly` rather than `--load`, because a multi-platform image cannot be loaded into the local daemon.

**Run it the way the cluster will**, which is the check that finds what `docker build` cannot — a missing writable path, or a UID that cannot read its own
files. Start the database first (`docker compose up -d`), then attach to its network:

```bash
docker run --rm --network event-junkie_default \
  --read-only --tmpfs /tmp --user 1000:1000 \
  -e SPRING_R2DBC_URL='r2dbc:postgresql://postgres:5432/event_junkie' \
  -e SPRING_R2DBC_USERNAME=admin -e SPRING_R2DBC_PASSWORD=admin \
  -e MANAGEMENT_SERVER_PORT=9001 -e SPRING_WEBFLUX_BASE_PATH=/api \
  -p 19002:9001 -p 18080:8080 event-junkie/bff:dev
```

The importer additionally needs the **JDBC** pair — `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER` (not `_USERNAME`) and `SPRING_FLYWAY_PASSWORD` — because it owns
the migrations and Flyway has no reactive driver. Locally under `bootRun` all of this is supplied by Spring Boot's Docker Compose support, which is
`developmentOnly` and therefore absent from the image; in a container nothing sets a URL unless you do.

### The frontend image

Same shape, different artefact: `npm run build` produces `dist/`, and the image is nginx plus that directory. The build context is the module, cut down to
`dist/` and `docker/` by `.dockerignore`:

```bash
npm --prefix events-frontend run build
docker buildx build events-frontend -t event-junkie/frontend:dev --load
docker run --rm --read-only --tmpfs /tmp --user 1000:1000 -p 8080:8080 event-junkie/frontend:dev
```

It needs no database and no backend, so it runs on its own — but **its API calls will 404**, and that is correct rather than broken. In a cluster the ingress
routes `/api` to the BFF and `/` to this container; nginx here proxies nothing.

What the config guarantees, and what is worth re-checking after any change to it:

| Request | Expected |
|---|---|
| `/` and any deep link (`/en/events/…`) | `200`, `index.html`, `Cache-Control: no-cache` |
| `/assets/<hashed>` | `200`, `immutable`, one year |
| `/assets/<missing>` | **`404`** — never the SPA fallback |
| `/sitemap.xml`, `/robots.txt` | `200`, one hour |
| `/.env` or any dotfile | `403` |

The images are **not pushed by CI** — that is the release workflow's job (#264).

## Running the whole stack on k3d

The chart, the three images and a real import, on a local Kubernetes. Per ADR-012 this is not an approximation of the production path — **it is the same chart
and the same images that run on Hetzner k3s**, which is what makes it worth doing. Needs `k3d` (`brew install k3d`) on top of the tools above.

```bash
scripts/k3d-rehearsal.sh all      # up → verify → import → chain → test → down
```

That is the whole loop, and it tears down even if something in the middle fails. The individual commands (`up`, `verify`, `import`, `chain`, `test`, `status`,
`down`) exist for when you want to keep the cluster and look at it; `scripts/k3d-rehearsal.sh --help` lists them. The agent-facing version is
[`/k3d-rehearsal`](../.github/prompts/k3d-rehearsal.prompt.md).

The steps live in the script rather than here on purpose — two copies of a sequence like this diverge, and the script is the one that gets run. What is worth
knowing before you read it:

- **Every `kubectl` and `helm` call names its context explicitly.** `k3d cluster create` switches the active context as a side effect, and most machines have
  other clusters — production ones among them — in the same kubeconfig. `down` restores whatever was current before.
- **The rehearsal uses its own database** (`event_junkie_k3d`), never the development one. Installing the chart runs Flyway, and pointing that at
  `event_junkie` would have the in-cluster importer fighting a local `bootRun` over one schema — with ~86 sources behind it that nobody wants to re-scrape.
- **Port 8080 must be free**, because that is where Traefik is published — and it is also the BFF's `bootRun` port. Stop `dev-env.sh` first.
- **CoreDNS needs a nudge.** k3d writes `host.k3d.internal` into the CoreDNS ConfigMap during cluster creation, but the `reload` plugin only picks it up on its
  next poll, up to 30 seconds later. Installing inside that window gives every pod `UnknownHostException: host.k3d.internal` and the importer crash-loops until
  DNS catches up — self-healing, which is worse than failing, because the install still succeeds and the only evidence is a restart count. The script forces the
  reload with a CoreDNS rollout restart. If you do this by hand, do the same.

**Check the content type, not the status code**, when testing what should *not* be reachable. nginx serves the SPA for every unmatched path, so
`/actuator/health` through the ingress returns **200** — and that 200 is `text/html`, the SPA fallback, not actuator. A negative test that only looks at the
status code passes for the wrong reason:

```bash
curl -s -o /dev/null -w '%{content_type}\n' -H 'Host: event-junkie.localhost' localhost:8080/actuator/health
# text/html  → the SPA. If this ever says application/json, actuator is exposed.
```

## Helm chart

The chart that deploys the three services onto that platform lives in [`deploy/charts/event-junkie/`](../deploy/charts/event-junkie). **It has never been
installed anywhere** — the images it references do not exist yet (#426, #262) and the first install is the k3d rehearsal in #263. Read
[deploy/AGENTS.md](../deploy/AGENTS.md) before changing it.

Needs `helm`, `yq` and `kubeconform` (`brew install helm yq kubeconform`). Everything below reaches no cluster and needs no kubeconfig, and is what
`validate-chart.yml` runs in CI:

```bash
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-staging.yaml
helm template t deploy/charts/event-junkie --values deploy/charts/event-junkie/values-staging.yaml
deploy/scripts/render-assertions.sh
shellcheck -x deploy/scripts/*.sh
```

`render-assertions.sh` is the one worth understanding: `helm lint` and `kubeconform` both pass on a chart that is well-formed, schema-valid and wrong — an
ingress that routes `/actuator`, an importer scaled past one replica, a selector carrying a label that changes on every release. It renders the chart once per
values file and asserts on the result.

Two things to know before running anything else. **`helm install --dry-run` is not safe here**: it resolves your current kubeconfig context and talks to that
cluster. Use `helm template`, or `--dry-run=client` if you need `NOTES.txt`. And **the base `values.yaml` cannot render on its own** — `database.host` and
`database.existingSecret` have no safe default, so add `--set database.host=10.0.1.2 --set database.existingSecret=events-db` when not using an environment
values file.

## Performance tests

[k6](https://k6.io) scripts against the BFF's read API live in [`perf/`](../perf). The BFF has to be running.

```bash
brew install k6
scripts/dev-env.sh up bff

k6 run perf/smoke.js     # every endpoint once — ~1s, safe anywhere, tolerates an empty DB
k6 run perf/load.js      # sustained load — watch whether p95 climbs with the VU count
k6 run perf/spike.js     # a sudden surge — the finding is whether it recovers
```

[`perf/README.md`](../perf/README.md) explains what each answers, how the thresholds were chosen and when to re-baseline them.

## Dependencies

### Checking for updates

```bash
./gradlew dependencyUpdates      # https://github.com/ben-manes/gradle-versions-plugin
```

Versions are centralised in [`gradle.properties`](../gradle.properties); plugin versions in
[`settings.gradle.kts`](../settings.gradle.kts). Frontend dependencies are pinned exactly — `npm outdated`, then
`npm update --save --save-exact`.

There is a [`/update-dependencies`](../.github/prompts/update-dependencies.prompt.md) skill that does this safely across both.

### Updating the Gradle wrapper

```bash
./gradlew wrapper --gradle-version latest
./gradlew wrapper --gradle-version 9.7.0     # or a specific version
```

### Licences and open-source notices

Every runtime dependency's licence is checked against a policy, and the full list is published on the site at
`/legal/notices`.

**Two checks, one policy.** They exist separately because the ecosystems report licence names differently — npm uses SPDX identifiers (`BSD-2-Clause`), the
Gradle plugin uses its normaliser's prose names (`The 2-Clause BSD License`). Change them together.

```bash
./gradlew checkLicense --no-configuration-cache      # JVM runtime dependencies
cd events-frontend && npm run check:licenses         # frontend production dependencies
```

Policies: [`config/allowed-licenses-jvm.json`](../config/allowed-licenses-jvm.json) and
[`config/allowed-licenses-npm.json`](../config/allowed-licenses-npm.json). A third gate,
[`dependency-review.yml`](../.github/workflows/dependency-review.yml), carries a deny-list applied to *newly introduced* dependencies at PR time.

> **Do not widen an allow-list to make a build pass.** AGPL, GPL without the Classpath Exception, and
> source-available licences (SSPL, BUSL, Elastic-2.0) are not acceptable for a public network service whose own
> source is Apache-2.0. **AGPL is the one to watch**: its § 13 obligation fires on *network interaction*, not
> distribution. See [LEGAL.md §9.2](LEGAL.md).

**Regenerating the notices page.** `events-frontend/src/assets/notices.json` is generated and committed — never hand-edited. Regenerate it whenever dependencies
change on either side:

```bash
./gradlew generateLicenseReport --no-configuration-cache   # → build/reports/dependency-license/licenses.json
cd events-frontend && npm run generate:notices             # merges both ecosystems into src/assets/notices.json
```

The generator writes no timestamp, so re-running it with unchanged dependencies produces an identical file and an empty diff. It is committed rather than
generated at build time because the frontend is not a Gradle subproject: its build must not have to invoke Gradle, and the page then works under `npm run dev`
with nothing else run first.
