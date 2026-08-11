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

Two more hooks run alongside it, both scoped to `infra/` and both `local` — they use the `tofu` and `shellcheck` already on your machine rather than pulling a
third-party hook repository that would need its own pinning:

| Hook | Runs on |
|---|---|
| `tofu-fmt` | any `.tf` / `.tfvars` file. It rewrites in place, so a failure means "re-stage and commit again", not "go and fix something" |
| `shellcheck-cloud-init` | `infra/modules/environment/cloud-init/*.sh` |

Both are also `validate-infra.yml`'s job in CI; the hooks just move the deterministic half of that feedback before the push. If you have neither tool
installed, the hooks fail — install them (`brew install opentofu shellcheck`) or skip with `git commit --no-verify` on a change that touches neither.

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
