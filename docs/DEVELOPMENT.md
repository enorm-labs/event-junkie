# Development

Everything needed to build, run and check this project locally. The [README](../README.md) gets you running in four
commands, and this document is the rest of it.

Frontend-specific development lives in [events-frontend/README.md](../events-frontend/README.md). The conventions
every change is held to are in [AGENTS.md](../AGENTS.md). That is written for AI agents, but it is simply this
project's conventions written down, and it is more complete than any other document here.

## The short version

```sh
sdk env                                   # the JDK from .sdkmanrc
brew install pre-commit && pre-commit install     # gitleaks + formatting hooks, before your first commit

scripts/dev-env.sh up all                 # importer + bff + frontend, each waited on until it answers
scripts/dev-env.sh seed-all               # register all event sources (needs ijhttp)
scripts/dev-env.sh import <slug>          # import one source, polling until it settles
scripts/dev-env.sh status                 # database / importer / bff / frontend
scripts/dev-env.sh down all               # add --db to stop Postgres too
```

Ports: frontend `5173`, BFF `8080`, importer `8081`, Postgres `56298`. Postgres is started for you by `bootRun` — there is no separate database setup step.

**Before opening a PR** — or run `/verify`, which does all of it plus the infra and chart gates when the diff touches them:

```sh
./gradlew clean build                     # compile, test, ktlint, detekt, coverage
cd events-frontend && npm run type-check && npm run lint && npm run test:unit && npm run test:e2e
scripts/format-markdown.sh                # any .md change
```

> **A green local build can still fail CI.** `build-backend.yml` sets `ORG_GRADLE_PROJECT_warningsAsErrors=true` for its whole job, and local builds do not.
> Add `-PwarningsAsErrors` to match it.

| Section                                                                           |                                         |
| --------------------------------------------------------------------------------- | --------------------------------------- |
| [Prerequisites](#prerequisites)                                                   | Tools and versions                      |
| [Git hooks (pre-commit)](#git-hooks-pre-commit)                                   | What runs on every commit, and why      |
| [Build and run](#build-and-run) · [The local database](#the-local-database)       | Gradle, Postgres, ports                 |
| [The `local` profile](#the-local-profile--logging-to-a-file)                      | Mirroring the console to a file         |
| [Running the stack with `dev-env.sh`](#running-the-stack-with-dev-envsh)          | The script above, in full               |
| [Calling the APIs](#calling-the-apis)                                             | Swagger UI and the IntelliJ HTTP Client |
| [Quality checks](#quality-checks)                                                 | ktlint, detekt, Kover, Markdown, OWASP  |
| [Infrastructure (OpenTofu)](#infrastructure-opentofu) · [Helm chart](#helm-chart) | The safe commands, and the gates        |
| [Container images](#container-images) · [k3d](#running-the-whole-stack-on-k3d)    | Building and running the stack locally  |
| [Versions and cutting a release](#versions-and-cutting-a-release)                 | One number, four files                  |
| [Performance tests](#performance-tests) · [Dependencies](#dependencies)           | k6, and keeping things current          |

## Prerequisites

| Tool         | Version                                   | Notes                                                             |
| ------------ | ----------------------------------------- | ----------------------------------------------------------------- |
| JDK          | see [`.sdkmanrc`](../.sdkmanrc)           | `sdk env` picks it up; install [SDKMAN](https://sdkman.io/) first |
| Docker       | any recent                                | Postgres is started for you by `bootRun`                          |
| Node.js      | see [`.nvmrc`](../events-frontend/.nvmrc) | frontend only; `nvm use`                                          |
| `pre-commit` | any                                       | for the commit hooks — `brew install pre-commit`                  |
| `gh`         | any recent                                | GitHub from the CLI — `brew install gh`, then `gh auth login`     |

The [GitHub CLI](https://cli.github.com/) is required, not optional. The agent prompts under [`.github/prompts/`](../.github/prompts) reach for it constantly.
They open pull requests, file issues and read CI status. A vendored skill in [`.claude/skills/gh/`](../.claude/skills/gh/SKILL.md) documents how to drive it,
copied from [`cli/cli`](https://github.com/cli/cli/tree/trunk/skills/gh). That skill documents the flags, not the install. Without the binary it is a page of
commands that all fail the same way.

**Agents get one shared MCP server.** [`.mcp.json`](../.mcp.json) declares `opentofu`, the
[hosted OpenTofu registry service](https://github.com/opentofu/opentofu-mcp-server) at `https://mcp.opentofu.org/mcp`. It looks providers, modules and resource
documentation up against the registry that `infra/` actually resolves against. It needs no API key and no install. Every clone gets it, because the file is
checked in. Claude Code then asks each person to approve it once, on first use. That approval is per person by design, and nothing in the repository waives it.

Adding one for yourself alone is `claude mcp add`, whose `--scope` **defaults to `local`**, meaning the directory you run it in and no other. Run it from your
home directory and it applies to your home directory. `--scope project` is what writes `.mcp.json`, and that is a change to the repository.

Optional, for specific jobs: [`ijhttp`](https://www.jetbrains.com/help/idea/http-client-cli.html) to run `.http` files
from the CLI, [`k6`](https://k6.io) for the performance tests, and [`tofu`](https://opentofu.org/) with `shellcheck`
for anything under `infra/`. The pre-commit hooks below need both of the last two.

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

Four more hooks run alongside it, all `local`. They use the `tofu`, `shellcheck` and `helm` already on your machine,
rather than pulling third-party hook repositories that would each need their own pinning:

| Hook                 | Runs on                                                                                                                      |
| -------------------- | ---------------------------------------------------------------------------------------------------------------------------- |
| `tofu-fmt`           | any `.tf` / `.tfvars` file. It rewrites in place, so a failure means "re-stage and commit again", not "go and fix something" |
| `shellcheck-scripts` | any `.sh` under `infra/` or `scripts/`                                                                                       |
| `format-markdown`    | any `.md` file. Also rewrites in place, so the same "re-stage and commit again" applies                                      |
| `helm-lint`          | anything under `deploy/charts/`. Lints the chart directory, so it takes no filenames                                         |

All four are also CI's job, in `validate-infra.yml`, `validate-chart.yml`, `validate-scripts.yml` and
`validate-docs.yml`. The hooks move the deterministic half of that feedback before the push. Without the tools
installed the hooks fail. Install them with `brew install opentofu shellcheck helm`, or skip them with
`git commit --no-verify` on a change that touches none of those paths.

`format-markdown` is the exception to "uses what is already on your machine". It deliberately calls the oxfmt pinned in
`events-frontend/package.json`, not one on `$PATH`, so it needs `npm ci` in `events-frontend/` rather than a
`brew install`. See [Markdown formatting](#markdown-formatting).

**CI's ShellCheck is pinned. Your local one is not.** All three CI jobs — `validate-scripts.yml`,
`validate-chart.yml`, `validate-infra.yml` — run `koalaman/shellcheck:v0.11.0` from Docker rather than the runner's
preinstalled binary. Renovate watches the pin, and groups the three copies into one pull request —
[ADR-024](adr/ADR-024_DEPENDENCY_UPDATE_BOUNDARY.md). The pre-commit hook uses whatever `shellcheck` is on your `$PATH`, so the two can disagree. The disagreement only
makes the hook noisier or quieter than the gate, never a false green on `main`.

That disagreement is real and worth knowing about, because versions differ in which checks they even have. An
`A && B || C` construct that 0.11.0 accepts is still `SC2015` on 0.9.0. That is why CI is pinned at all. Before the
pin, a script the author's Homebrew build cleared arrived red on the runner. The temptation then is to contort the
source until the older analyser is happy, rather than to fix the version skew. To reproduce a CI result exactly:

```bash
docker run --rm -v "$PWD:/mnt" -w /mnt koalaman/shellcheck:v0.11.0 -x scripts/*.sh
```

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

`bootRun` also starts the services in [`compose.yaml`](../compose.yaml) — PostgreSQL and MinIO — via Spring
Boot's [Docker Compose support](https://docs.spring.io/spring-boot/reference/features/dev-services.html#features.dev-services.docker-compose).

**`bootRun` runs with the module as its working directory, and `compose.yaml` is at the repository root.** Spring therefore cannot find it on its own. The root
`build.gradle.kts` sets `spring.docker.compose.file` on every `bootRun` task, which is what makes the commands above work. Do not remove it: without it the
application stops at startup with `No Docker Compose file found in directory '.../events-importer/.'`.

An IntelliJ run configuration that starts the application directly does not go through Gradle, so it does not get that setting. If it stops with the same
message, set its working directory to the repository root.

Ports: importer `8081`, BFF `8080`, frontend `5173`, Postgres `56298`.

## The local database

PostgreSQL is exposed on host port **56298**, mapped from the container's 5432. Spring Boot discovers the port itself,
and you need it only to connect by hand: `localhost:56298`, credentials `admin` / `admin`, database `event_junkie`.

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

Both services define a `local` Spring profile whose only effect is to mirror console output to a file. An import or a
request run can then be grepped afterwards, rather than scrolled in the IDE console.

| Service           | Log file                                     |
| ----------------- | -------------------------------------------- |
| `events-importer` | `events-importer/build/dev-env/importer.log` |
| `events-bff`      | `events-bff/build/dev-env/bff.log`           |

```bash
./gradlew :events-importer:bootRun --args='--spring.profiles.active=local'
```

In IntelliJ, set **Active profiles: `local`** on the run configuration. Paths are relative to each module directory (`bootRun`'s working directory) and land
under `build/`, which is gitignored.

**The profile gate is deliberate.** On a container platform the log belongs on stdout, where the platform collects it.
A file appender there would write into the container's in-memory filesystem. `scripts/dev-env.sh` does not need the
profile, because it redirects each service's stdout to `build/dev-env/<service>.log` itself.

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

The OpenAPI document (JSON) is at `/v3/api-docs` on each port. **The BFF's document is also the source of the
frontend's TypeScript types** — see
[events-frontend/README.md](../events-frontend/README.md#regenerate-the-api-types-after-a-bff-change). Changing the
BFF's public API means regenerating them in the same PR.

### IntelliJ HTTP Client

[`http/`](../http) holds request files, split by service:

- [`http/importer/`](../http/importer) — the admin CRUD endpoints (venues, artists, promoters, events, sources, dev seed) plus health and OpenAPI checks.
- [`http/bff/`](../http/bff) — the public read API (events, venues, artists, genres) plus health and OpenAPI checks.
- [`http/google/`](../http/google) — the Google Geocoding lookups behind `scripts/geocode-venues.py`. Billed, and they need the key below.
- [`http/osm/`](../http/osm) — OpenStreetMap, in two files because they are two services. `nominatim.http` searches by name or address, and reverse-geocodes a
  point. `overpass.http` asks what is tagged at a place. That is how "does house number 114 exist on this street" gets answered. No key, and **results are
  ODbL**. The coordinates in the `venue` table therefore come from here, and Google is only ever the detector (V013–V015).

The `google/` and `osm/` files are the only ones that leave the machine, and none of the three is in `./gradlew httpTest`.

The shared `http-client.env.json` sits at the `http/` root (IntelliJ resolves it from parent directories) and defines `importer-host` and `bff-host` for three
environments. **`local` is the only one that points at something on this machine.** `staging` and `production` point
`importer-host` at `localhost:18081` and `localhost:28081`. Those are the two port-forwards in [CLUSTER_ACCESS.md](ops/CLUSTER_ACCESS.md) §6a. The ports differ
on purpose: a forward that lands on the wrong stack is how you seed the wrong database. Nothing listens on either port until you open that tunnel. Both
environments are therefore inert by default, not one dropdown away from a live write.

### The private environment file

`google-maps-api-key` appears in no tracked file except the template below. A secret's name belongs with the secret, so the public env file holds hosts only.
The value goes in `http-client.private.env.json` beside it, which merges over the public file by environment name:

```bash
cp http/http-client.private.env.json.example http/http-client.private.env.json
```

Three things about that file are easy to get wrong, and two of them fail silently:

- **It is gitignored here, and IntelliJ does not do that for you.** JetBrains' own documentation says the file "is not tracked by Git. However, it is not added
  to the `.gitignore` file" — the IDE hides it from its own commit dialog and nothing else. A `git add` from the terminal would have committed it, so
  `.gitignore` carries the entry explicitly.
- **The IDE picks the file up automatically. `ijhttp` does not.** Without `--private-env-file` nothing defines the variable. The request then goes out with a
  literal `key={{google-maps-api-key}}`, which the logged request line shows and Google refuses.
- **One key covers all three environments.** Geocoding is a lookup you run while adding a venue, not something the deployed stack calls. Separate keys are
  worth having only for separate quota, billing or revocation.

```bash
cd http
ijhttp --env-file http-client.env.json --private-env-file http-client.private.env.json \
       --env local google/geocoding.http
```

`scripts/geocode-venues.py` reads the same file directly, so the key is stored once for both tools. Restrict it in the Cloud console to the Geocoding API, and
by IP address rather than by HTTP referrer. The key is used from a shell, not a browser.

**From IntelliJ:** start the service, open a `.http` file, select the **local** environment, click ▶. Create requests store their response IDs (e.g.
`{{venue_id}}`), so later update/delete/event requests reference them without copy-paste.

**From the command line**, via the [HTTP Client CLI](https://www.jetbrains.com/help/idea/http-client-cli.html) — no IntelliJ Ultimate licence required:

```bash
brew install ijhttp

./gradlew httpTest      # the full CRUD lifecycle scenario; needs the importer on :8081

cd http
ijhttp --env-file http-client.env.json --env local importer/venues.http
ijhttp --env-file http-client.env.json --env local -L VERBOSE importer/full-lifecycle.http
```

## Quality checks

Everything below runs in CI too. The point of running it locally is not to find out from a red build.

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

### Markdown formatting

Every `.md` file in the repository is formatted by [oxfmt](https://oxc.rs/docs/guide/usage/formatter.html), through one script:

```bash
scripts/format-markdown.sh          # format in place
scripts/format-markdown.sh check    # report drift, write nothing; exits 1 if anything is unformatted
```

Enforced in two places: the `format-markdown` pre-commit hook, which formats on the way in, and `validate-docs.yml`,
which runs `check` in CI. The hook is advisory by construction, because `--no-verify` skips it, as does anything
committed through the web UI. CI is what makes it hold on `main`. CI checks and never writes. A job that pushes a
formatting commit back would need write access on every pull request, forks included. That is more than a formatter is
worth.

Configuration is the root [`.oxfmtrc.json`](../.oxfmtrc.json): tables aligned, `_emphasis_` over `*emphasis*`, `-`
bullets, and prose left exactly where the author wrapped it (`proseWrap: preserve`). Reflowing a 150-column paragraph
would turn every prose edit into a whole-paragraph diff.

Four things about this are deliberate and easy to undo by accident:

- **It is Markdown-only, enforced twice.** oxfmt also formats YAML, JSON, CSS and TypeScript. The script never passes
  it anything else, _and_ `.oxfmtrc.json` carries an `ignorePatterns` deny-list for those extensions. Even a bare
  `oxfmt` run at the repository root therefore cannot touch them. Do not widen either one.
  [.github/instructions/markdown.instructions.md](../.github/instructions/markdown.instructions.md) records what was
  measured and why the answer was no.
- **It uses the pinned oxfmt, never `$PATH`.** oxfmt is pre-1.0 and its Markdown output is not stable across versions.
  The binary is locked by `package-lock.json` like everything else, so the hook needs `npm ci` in `events-frontend/`
  rather than a `brew install`. 0.62.0 and 0.63.0 happen to agree here, verified byte-for-byte on every tracked file.
  This is insurance, not a workaround for a known disagreement.
- **oxfmt reads `.editorconfig`.** The `[*] indent_size = 4` is what gives nested list items their four-space indent.
  Copy `.editorconfig` alongside if you ever reproduce oxfmt's behaviour in a scratch directory. Without it the output
  differs, and it differs in a way that looks exactly like a version disagreement.
- **Write mode runs oxfmt twice, permanently.** A table indented under a list item is skipped on the first pass and
  only formatted on the second. One pass would leave the file off its own fixpoint, and `check` would then fail on a
  file the formatter wrote a moment earlier.

    **This is intended behaviour, not a version bug. Do not go looking for the release that fixes it.** Prettier does
    exactly the same thing, with the same two passes and the same intermediate output, and oxfmt targets Prettier
    compatibility. Upstream closed [oxc-project/oxc#25612](https://github.com/oxc-project/oxc/issues/25612) as _not
    planned_ on that basis. The second run is a permanent part of how this script works, and it costs a few hundred
    milliseconds on a ~100-file tree. Removing it breaks `check` on `AGENTS.md`, the file in this repository that
    exhibits the shape.

- **`--disable-nested-config`**, because oxfmt's nested configs _replace_ rather than merge. Without it,
  `events-frontend/.oxfmtrc.json` shadows the root config wholesale for the two `.md` files under
  `events-frontend/`.

IntelliJ has its own Markdown formatter, and it does not agree with oxfmt about table padding. The conflicting
`ij_markdown_*` keys were removed from `.editorconfig`. That unpins IntelliJ's settings rather than disabling its
formatter, so Reformat Code on a `.md` file still reflows it. The commit hook runs oxfmt last and normalises the
result, so commits stay consistent either way. The tidy habit is to leave Markdown to the script.

### Dependency CVE scanning (OWASP Dependency-Check)

Scans every dependency against the [NVD](https://nvd.nist.gov/). **The build fails on CVSS ≥ 7 (HIGH).**

```bash
./gradlew dependencyCheckAggregate --no-configuration-cache
```

Reports land in `build/reports/`: `dependency-check-report.html` and `.sarif` (the latter uploaded to GitHub Code Scanning). False positives are suppressed in [
`owasp-suppressions.xml`](../owasp-suppressions.xml). The
`--no-configuration-cache` flag is required — the plugin is not configuration-cache compatible.

**Get an NVD API key.** Unauthenticated requests are rate-limited hard enough to make the first database download take
10+ minutes. A free key brings it to about one.

1. Request one at <https://nvd.nist.gov/developers/request-an-api-key>
2. Locally: `export NVD_API_KEY=your-key-here`
3. In CI: repository secret named `NVD_API_KEY`

## Infrastructure (OpenTofu)

The Hetzner platform is declared in [`infra/`](../infra). **Nothing in it has ever been applied**, and the plan behind it is
[docs/ops/PLATFORM_SETUP.md](./ops/PLATFORM_SETUP.md).

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

**`tofu plan` and `tofu apply` are not part of local verification.** They need a Hetzner API token and they spend
money. See [infra/README.md](../infra/README.md) for who runs them and in what order. One consequence is worth knowing
rather than discovering: `validate` does **not** render `templatefile`. A change to the cloud-init template can
therefore pass every check here and still produce YAML that a booting server rejects.

## Container images

`events-bff` and `events-importer` each build to a container image. The Dockerfiles are runtime-only. Gradle explodes
the fat jar into its layers first, and the **build context is that output directory**, not the module:

> **The runtime base is `bellsoft/liberica-openjre-alpine`, not Temurin, and the build JDK is still Temurin** —
> [ADR-017](adr/ADR-017_JRE_BASE_IMAGE.md) has the reasoning. Nothing about your local workflow changes: `.sdkmanrc`
> is unchanged and you compile with what you always did. It matters in one place. The image runs on **musl**, so a
> JVM-level difference would appear only in a container, never in `bootRun` or a unit test. The read-only run below is
> the cheapest way to catch that, and the k3d rehearsal is the thorough one.

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

**Run it the way the cluster will.** That is the check that finds what `docker build` cannot: a missing writable path,
or a UID that cannot read its own files. Start the database first (`docker compose up -d`), then attach to its
network:

```bash
docker run --rm --network event-junkie_default \
  --read-only --tmpfs /tmp \
  -e SPRING_R2DBC_URL='r2dbc:postgresql://postgres:5432/event_junkie' \
  -e SPRING_R2DBC_USERNAME=admin -e SPRING_R2DBC_PASSWORD=admin \
  -e MANAGEMENT_SERVER_PORT=9001 \
  -p 19002:9001 -p 18080:8080 event-junkie/bff:dev
```

The importer additionally needs the **JDBC** trio: `SPRING_FLYWAY_URL`, `SPRING_FLYWAY_USER` (not `_USERNAME`) and
`SPRING_FLYWAY_PASSWORD`. It owns the migrations, and Flyway has no reactive driver. Locally under `bootRun`, Spring
Boot's Docker Compose support supplies all of this. That support is `developmentOnly` and therefore absent from the
image, so in a container nothing sets a URL unless you do.

### The frontend image

Same shape, different artefact: `npm run build` produces `dist/`, and the image is nginx plus that directory. The build context is the module, cut down to
`dist/` and `docker/` by `.dockerignore`:

```bash
npm --prefix events-frontend run build
docker buildx build events-frontend -t event-junkie/frontend:dev --load
docker run --rm --read-only --tmpfs /tmp -p 8080:8080 event-junkie/frontend:dev
```

It needs no database and no backend, so it runs on its own. But **its API calls will 404**, and that is correct rather
than broken. In a cluster the ingress routes `/api` to the BFF and `/` to this container. Here, nginx proxies
nothing.

What the config guarantees, and what is worth re-checking after any change to it:

| Request                                | Expected                                       |
| -------------------------------------- | ---------------------------------------------- |
| `/` and any deep link (`/en/events/…`) | `200`, `index.html`, `Cache-Control: no-cache` |
| `/assets/<hashed>`                     | `200`, `immutable`, one year                   |
| `/assets/<missing>`                    | **`404`** — never the SPA fallback             |
| `/sitemap.xml`, `/robots.txt`          | `200`, one hour                                |
| `/.env` or any dotfile                 | `403`                                          |

The images are **not pushed by CI** — that is the release workflow's job (#264).

## Running the whole stack on k3d

The chart, the three images and a real import, on a local Kubernetes. Per ADR-012 this is not an approximation of the
production path. **It is the same chart and the same images that run on Hetzner k3s**, which is what makes it worth
doing. Needs `k3d` (`brew install k3d`) on top of the tools above.

```bash
scripts/k3d-rehearsal.sh all      # up → verify → import → chain → test → down
```

That is the whole loop, and it tears down even if something in the middle fails. The individual commands exist for
when you want to keep the cluster and look at it: `up`, `verify`, `import`, `chain`, `test`, `status`, `down`.
`scripts/k3d-rehearsal.sh --help` lists them. The agent-facing version is
[`/k3d-rehearsal`](../.github/prompts/k3d-rehearsal.prompt.md).

The steps live in the script rather than here on purpose. Two copies of a sequence like this diverge, and the script is
the one that gets run. What is worth knowing before you read it:

- **Every `kubectl` and `helm` call names its context explicitly.** `k3d cluster create` switches the active context as a side effect, and most machines have
  other clusters — production ones among them — in the same kubeconfig. `down` restores whatever was current before.
- **The rehearsal uses its own database** (`event_junkie_k3d`), never the development one. Installing the chart runs
  Flyway. Pointing that at `event_junkie` would have the in-cluster importer fighting a local `bootRun` over one
  schema, with ~86 sources nobody wants to re-scrape.
- **Port 8080 must be free**, because that is where Traefik is published. It is also the BFF's `bootRun` port, so stop
  `dev-env.sh` first.
- **CoreDNS needs a nudge.** k3d writes `host.k3d.internal` into the CoreDNS ConfigMap during cluster creation. The
  `reload` plugin only picks it up on its next poll, up to 30 seconds later. Installing inside that window gives
  every pod `UnknownHostException: host.k3d.internal`, and the importer crash-loops until DNS catches up. That is
  self-healing, which is worse than failing: the install still succeeds, and the only evidence is a restart count. The
  script forces the reload with a CoreDNS rollout restart. If you do this by hand, do the same.
- **A TLS-inspecting proxy breaks the cluster before any of our code runs, and it does not look like a proxy
  problem.** The node pulls images with its own containerd, which has its own CA trust store. A corporate MITM proxy
  whose root CA is installed in macOS and Docker Desktop is still unknown inside the k3d node. `docker pull` from your
  shell succeeds, and the node's pull of the same image fails.

    The symptom is **every pod stuck in `ContainerCreating`**: `coredns`, `metrics-server`,
    `local-path-provisioner` and the Traefik installers. That reads like resource exhaustion, so the reflex is to go
    looking at Docker's memory and disk, where there is nothing to find. The cause is only visible in a `describe`:

    ```bash
    kubectl --context k3d-event-junkie describe pod -n kube-system -l k8s-app=kube-dns | grep -A3 FailedCreatePodSandBox
    # failed to pull image "rancher/mirrored-pause:3.6": … x509: certificate signed by unknown authority
    ```

    `rancher/mirrored-pause` is the infrastructure container Kubernetes puts in **every** pod. That is why nothing at
    all starts, rather than only our three workloads. Confirm it in one line before suspecting anything else. TLS from
    inside a container takes the same path the node does:

    ```bash
    docker run --rm alpine wget -q -O- 'https://auth.docker.io/token?service=registry.docker.io&scope=repository:rancher/mirrored-pause:pull'
    # a JSON token → fine. An SSL/x509 error → this is the problem.
    ```

    Turning the proxy off is the fix. Trusting its CA inside the node would also work, and is more work than it is
    worth for a throwaway cluster. **Cost when it bit: the chart install timed out after five minutes and every
    routing assertion failed.** That looks exactly like a broken change, and is not one.

**Check the content type, not the status code**, when testing what should _not_ be reachable. The nginx container
serves the SPA on every unmatched path, so `/actuator/health` through the ingress returns **200**. That 200 is `text/html`, the SPA
fallback, not actuator. A negative test that reads only the status code passes for the wrong reason:

```bash
curl -s -o /dev/null -w '%{content_type}\n' -H 'Host: event-junkie.localhost' localhost:8080/actuator/health
# text/html  → the SPA. If this ever says application/json, actuator is exposed.
```

## Helm chart

The chart that deploys the three services onto that platform lives in
[`deploy/charts/event-junkie/`](../deploy/charts/event-junkie). It was **installed and exercised on k3d** (#263), and
runs on **no real cluster**. Those are different claims, and the section above is what keeps the first one true. Read
[deploy/AGENTS.md](../deploy/AGENTS.md) before changing it.

Needs `helm`, `yq` and `flux` with its schema plugin, plus the helm-unittest plugin:

```bash
brew install helm yq fluxcd/tap/flux && flux plugin install schema
helm plugin install https://github.com/helm-unittest/helm-unittest --version v1.1.2 --verify=false
```

`--verify=false` is a Helm 4 requirement. Helm 4 refuses an unverifiable plugin source without it, and the local
binary is v4. CI pins Helm 4 too (`HELM_VERSION`) and installs the plugin with the same flag. Pin whatever
`HELM_UNITTEST_VERSION` in `validate-chart.yml` pins: a gate whose plugin version floats is a gate whose verdict floats.

Everything below reaches no cluster and needs no kubeconfig, and is what `validate-chart.yml` runs in CI:

```bash
helm lint --strict deploy/charts/event-junkie --set database.host=10.0.1.2 --set database.existingSecret=events-db
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
helm unittest --strict deploy/charts/event-junkie
scripts/cluster-assertions.sh
```

There is no `values-staging.yaml`. Since #414, staging's and production's configuration lives in each cluster's
`HelmRelease` under `spec.values`. A HelmRelease cannot read a file from this repository, and two copies would drift.

`helm unittest` is the pair worth understanding, with `scripts/cluster-assertions.sh`. `helm lint` and schema
validation both pass on a chart that is well-formed, schema-valid and wrong. An ingress that routes `/actuator`, an
importer scaled past one replica, a selector carrying a label that changes on every release. The suites in
`deploy/charts/event-junkie/tests/` assert against those. The script re-runs them against the `spec.values` of every
cluster's `HelmRelease`, so they gate what Flux actually deploys. Five renders in total. They also assert that the
chart _refuses_ to render without `database.host` or `database.existingSecret`, and that it says what to do about it.
That refusal is the interface a first-time installer meets.

Two things to know before running anything else. **`helm install --dry-run` is not safe here.** It resolves your
current kubeconfig context and talks to that cluster. Use `helm template`, or `--dry-run=client` if you need
`NOTES.txt`. And **the base `values.yaml` cannot render on its own**. `database.host` and `database.existingSecret`
have no safe default, so add `--set database.host=10.0.1.2 --set database.existingSecret=events-db` when not using an
environment
values file.

## Versions and cutting a release

> The **end-to-end** picture — build, scan, publish, and how Flux reconciles it onto a cluster, with a diagram — is [RELEASING.md](ops/RELEASING.md). This section is
> the version scheme and the local commands.

**One number reaches four artifacts, and only one file decides it.** [`gradle.properties`](../gradle.properties)
carries `version=X.Y.Z-SNAPSHOT`, and everything else derives from it. Three other files repeat the number, and none
is authoritative:

| File                           | Holds            | Why it is not the source                                                       |
| ------------------------------ | ---------------- | ------------------------------------------------------------------------------ |
| `gradle.properties`            | `0.3.1-SNAPSHOT` | **The source of truth.** `bootBuildInfo` stamps it, `/actuator/info` serves it |
| `events-frontend/package.json` | `0.3.1`          | npm has no `-SNAPSHOT` convention, so it mirrors the bare number               |
| `Chart.yaml` `version`         | `0.3.1`          | A placeholder — the release workflow stamps the computed version over it       |
| `Chart.yaml` `appVersion`      | `0.3.1`          | The same placeholder, and also the default image tag for all three components  |

[`scripts/version.sh`](../scripts/version.sh) is the only thing that knows the rules, so CI and a laptop always agree:

```bash
scripts/version.sh base       # 0.3.1 — the released number this tree is heading for
scripts/version.sh compute    # 0.3.1-snapshot.20260814122042.g33fd32g on a branch; 0.3.1 from the tag v0.3.1
scripts/version.sh check      # fails if the four files disagree — also a pre-commit hook
scripts/version-test.sh       # fails if snapshot versions stop ordering — needs helm
```

**Snapshots are prereleases _of the coming release_, not of the last one.** SemVer sorts
`0.3.1-snapshot.20260814122042.g33fd32g` _before_ `0.3.1`. Naming a snapshot after the released version would have it
claim to be older than code it is newer than. Same semantics as Maven's `-SNAPSHOT`.

**The timestamp is there so that snapshots _order_, and that is not a detail**
([#455](https://github.com/enorm-labs/event-junkie/issues/455)). SemVer §11 compares a digits-only identifier
numerically, and one containing a letter lexically in ASCII. The previous scheme — `0.1.0-snapshot.g<sha>` — therefore
sorted by short sha, which is random. Staging's `semver: ">=0.0.0-0"` range resolved whichever sha sorted highest
rather than the newest chart, silently, while reporting `Ready`. It ran a three-day-old chart until the symptom turned
up somewhere unrelated. The timestamp is `YYYYMMDDHHMMSS` in UTC, taken from the commit's **committer date** rather
than from the clock. `compute` therefore stays a pure function of the commit, and a workflow re-run cannot produce a
second name for identical artifacts.

The same §11 rule is why the base version moved `0.1.0` → `0.1.1` without `0.1.0` ever shipping. A numeric identifier
ranks **below** an alphanumeric one, so a timestamped snapshot of `0.1.0` would have sorted under every legacy
`0.1.0-snapshot.g…` tag already in GHCR. Those tags are immutable and were not deleted.

The `g` before the sha is git-describe's convention, and it is load-bearing rather than decorative. A SemVer identifier
made only of digits may not have a leading zero. A short sha like `0031234` would therefore produce a version that
`helm lint --strict` rejects outright. That is about one commit in 270, being `(10/16)^6 / 16` for a sha uniform over
hex.

**It does not help the ordering**, which is worth stating because the opposite is easy to assume. Prerelease
identifiers are compared left to right, and the comparison stops at the first difference. The timestamp decides, and
the sha is reached only when two timestamps are equal. In that same-second tie the `g` buys one small thing. Every sha
is then alphanumeric, so ties break by plain ASCII. Bare shas would be a mix, and SemVer ranks every numeric
identifier below every non-numeric one. An all-digit sha would lose every tie regardless of its value.

### What gets published, and when

[`release.yml`](../.github/workflows/release.yml) builds, scans and pushes **four artifacts** from one computed
version: three images and the chart. It does not deploy. Flux pulls from GHCR on its own schedule (#414), so a green
run means the artifacts exist, not that they are live.

| Trigger                                         | Version                                 | Published                                         |
| ----------------------------------------------- | --------------------------------------- | ------------------------------------------------- |
| every push to `main`                            | `0.3.1-snapshot.<utc-timestamp>.g<sha>` | images + chart                                    |
| **publishing a GitHub Release** tagged `v0.3.1` | `0.3.1`                                 | images + chart, **and** `latest` on the images    |
| a PR touching `release.yml` or `version.sh`     | snapshot                                | **nothing**                                       |
| `workflow_dispatch`                             | as above                                | **nothing**, unless the `publish` input is ticked |

Publishing is an allowlist: `push` events, and a dispatch that explicitly asks for it. It is not "everything except
the dry run", so a trigger added later cannot quietly become a publishing one.

**The workflow tests itself, because it is the one workflow that cannot be tried before it is trusted.**
`workflow_dispatch` is offered only for a workflow already on the default branch. A dispatch button added in a pull
request therefore does not exist until that request merges, and merging is exactly what publishes. Hence the
`pull_request` trigger. A change to `release.yml` or `scripts/version.sh` runs the whole thing on the PR, and pushes
none of it. Three images built and scanned, and the chart stamped, linted and packaged.

### Cutting a release

**Releases are cut through [GitHub Releases](https://github.com/enorm-labs/event-junkie/releases), not by pushing a tag.** The workflow triggers on
`release: published`, so a tag pushed from a laptop publishes nothing — deliberately. That keeps the Releases page the single record of what was released, with
the notes `.github/release.yml` generates from the merged PRs' labels.

```bash
# Dry run first: resolves the version, creates nothing.
gh workflow run cut-release.yml -f dry_run=true

# Then for real. `bump` is patch by default.
gh workflow run cut-release.yml -f dry_run=false -f bump=patch
```

[`cut-release.yml`](../.github/workflows/cut-release.yml) reads the version from `gradle.properties`. It does both
halves. First it publishes the release. Then it opens the pull request that moves `main` to the next snapshot. **The
version is never typed**, so the tag cannot claim a number the tree does not carry.

The second half is the one that matters, and it was the step a person could skip without noticing. Until `main` carries
the next snapshot, staging stops following it. Snapshots of the just-released version sort _below_ the release, so the
`>=0.0.0-0` range keeps resolving the release itself, and nothing reports it
([#455](https://github.com/enorm-labs/event-junkie/issues/455)).

`bump` takes `minor` or `major` for a release that earned one. **`patch` is the default because it assumes least.** A
snapshot is a prerelease of the coming release. So `0.4.0-SNAPSHOT` decides the next release is a minor one, before
anybody knows what is in it.

By hand, if the workflow is unavailable:

```bash
scripts/version.sh check
gh release create v0.3.1 --target main --generate-notes
scripts/version.sh bump patch      # writes all four files; commit them on a branch
```

A release version is **never committed** — `release.yml` passes `-Pversion=` from the tag, so the tag and the built artifacts cannot disagree. Tagging `v0.4.0`
on a tree that still says `0.3.1-SNAPSHOT` fails immediately in `scripts/version.sh compute`, before anything is built.

Triggering on `published` rather than on the tag has two consequences. A **draft** release creates no tag and
publishes nothing until you publish it, which makes drafting notes safe. And a release cut from a tag that already
exists still triggers, which a tag-push trigger would not. **Pre-releases are not supported** by the version scheme.
`v0.1.0-rc1` fails the match against `gradle.properties`, and snapshots already fill that role.

**`latest` is publish-only.** It is a human pointer at the newest release, and nothing in the deploy path may consume
it. With `imagePullPolicy: IfNotPresent`, a mutable tag lets two nodes run different code and neither is wrong. The
chart's helm-unittest suites fail the build on a floating tag, which is that rule enforced from the consuming side.

### Two things that are clicks, not code

- **Every GHCR package is private on its first publish**, regardless of repository visibility. Four packages — `bff`, `importer`, `frontend` and the chart —
  each needing one visibility flip in its package settings. The symptom of forgetting is `ImagePullBackOff` on the first deploy, with nothing in the logs
  naming the cause. See [PLATFORM_SETUP §3](ops/PLATFORM_SETUP.md#3-container-registry--ghcr-not-docker-hub).
- **A local `docker push` or `helm push` needs a classic PAT** with `write:packages`, because GitHub Packages does not
  support a fine-grained token. CI needs no such credential: `permissions: packages: write` and the run's own token
  are enough.

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

Versions are centralised in [`gradle.properties`](../gradle.properties), and plugin versions in
[`settings.gradle.kts`](../settings.gradle.kts). Frontend dependencies are pinned exactly: `npm outdated`, then
`npm update --save --save-exact`.

There is a [`/update-dependencies`](../.github/prompts/update-dependencies.prompt.md) skill that does this safely across both.

### Updating the Gradle wrapper

**Pass the checksum every time, or the bump removes it.** `gradle-wrapper.properties` carries a `distributionSha256Sum`, and `./gradlew wrapper` writes the
file from its arguments. A run without the flag drops the line, and the wrapper goes back to verifying only the URL. Nothing fails when it does.

```bash
SUM=$(curl -fsSL https://services.gradle.org/distributions/gradle-9.7.1-bin.zip.sha256)
./gradlew wrapper --gradle-version 9.7.1 --gradle-distribution-sha256-sum "$SUM"
```

Renovate bumps this wrapper by itself and maintains the checksum with it, so the command above is for a bump taken by hand.

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
[`dependency-review.yml`](../.github/workflows/dependency-review.yml), carries a deny-list applied to _newly introduced_ dependencies at PR time.

> **Do not widen an allow-list to make a build pass.** AGPL, GPL without the Classpath Exception, and
> source-available licences (SSPL, BUSL, Elastic-2.0) are not acceptable for a public network service whose own
> source is Apache-2.0. **AGPL is the one to watch**: its § 13 obligation fires on _network interaction_, not
> distribution. See [LEGAL.md §9.2](LEGAL.md).

**Regenerating the notices page.** `events-frontend/src/assets/notices.json` is generated and committed — never hand-edited. Regenerate it whenever dependencies
change on either side:

```bash
./gradlew generateLicenseReport --no-configuration-cache   # → build/reports/dependency-license/licenses.json
cd events-frontend && npm run generate:notices             # merges both ecosystems into src/assets/notices.json
```

The generator writes no timestamp, so re-running it with unchanged dependencies produces an identical file and an
empty diff. It is committed rather than generated at build time, because the frontend is not a Gradle subproject. Its
build must not have to invoke Gradle, and the page then works under `npm run dev` with nothing else run first.
