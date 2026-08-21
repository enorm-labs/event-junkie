# Event Junkie

[![Build & Test Backend](https://github.com/enorm-labs/event-junkie/actions/workflows/build-backend.yml/badge.svg)](https://github.com/enorm-labs/event-junkie/actions/workflows/build-backend.yml)
[![Build & Test Frontend](https://github.com/enorm-labs/event-junkie/actions/workflows/build-frontend.yml/badge.svg)](https://github.com/enorm-labs/event-junkie/actions/workflows/build-frontend.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)
[![Status](https://img.shields.io/badge/Status-In%20Development-orange.svg)](#status)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.0-6DB33F.svg?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-25-ED8B00.svg?logo=openjdk&logoColor=white)](https://openjdk.org)
[![Vue.js](https://img.shields.io/badge/Vue.js-3-4FC08D.svg?logo=vuedotjs&logoColor=white)](https://vuejs.org)

Every music event in Berlin, in one filterable feed — collected automatically from the venues' own websites.

> **The event app Berlin deserves.**

**Event Junkie** is the name everywhere — the app, the repository and the identifiers, which use the `event-junkie` form. See the naming rule
in [BRANDING.md](./docs/BRANDING.md).

## Contents

- [Background](#background)
- [Status](#status)
- [Built with AI](#built-with-ai)
- [Install](#install)
- [Usage](#usage)
- [Architecture](#architecture)
- [API](#api)
- [Documentation](#documentation)
- [Contributing](#contributing)
- [Support](#support)
- [Maintainers](#maintainers)
- [License](#license)

## Background

Berlin's scene is enormous and completely scattered. What's on lives across dozens of venue and promoter websites, each with its own layout and its own gaps, so
answering something as ordinary as _what's on near me this weekend, in my genre, that I can afford?_ means a dozen browser tabs and a lot of guessing.

The existing options each solve a slice of it. Resident Advisor is excellent at electronic music and only that. Bandsintown and Songkick follow _artists_ — no
help when you want to know what is happening on Thursday. Ticketing sites list what they sell, which quietly excludes free entry, door-only nights and the small
rooms.

Event Junkie is the thing none of them tries to be: **one feed for all of it** — every kind of venue, every genre, free and ticketed alike, always linking back
to the venue's own page for tickets and the final word.

The scope rule, in one line: **if a Berlin venue puts it on a stage in the evening, it is in scope.** What that includes, what is deliberately excluded and
which coverage questions are still open is in
[EVENT_SCOPE.md](./docs/EVENT_SCOPE.md). What the product is and does today is in
[PRODUCT_OVERVIEW.md](./docs/PRODUCT_OVERVIEW.md); where it is headed is in
[VISION_ROADMAP_IDEAS.md](./docs/VISION_ROADMAP_IDEAS.md).

## Status

🚧 **In development — deployed, but not public yet.**

The product works end-to-end: 86 Berlin sources are imported on a schedule, and the frontend, BFF and importer run locally and on both Hetzner environments.
Staging and production are stood up and reconciling under Flux — production is **dark**, serving nothing publicly until the domain is pointed at it at go-live.
What is left is the legal, auth and go-live work, tracked in the [`v0.3` and `v1.0` milestones](https://github.com/enorm-labs/event-junkie/milestones).

One consequence worth knowing before you build on this: **the database schema is still evolving and offers no migration compatibility between versions.** All
schema changes are consolidated into a single initial migration (`V001`) until the first production release.

## Built with AI

Most of the code in this repository was written by AI coding agents (primarily
[Claude Code](https://claude.com/claude-code)), working from the prompts and skills in
[`.github/prompts/`](./.github/prompts) and the conventions in [AGENTS.md](./AGENTS.md).

The vision, the product ideas, the architecture decisions and the priorities are mine. The agents implement against them; every change goes through review
before it lands.

This is also _why_ the project exists. It is a real application with real constraints, which turns out to be the only honest way to find out what this way of
working is genuinely good at and where it still needs a human paying attention.

## Install

Prerequisites: a JDK (see [`.sdkmanrc`](./.sdkmanrc), managed with [SDKMAN](https://sdkman.io/)), Docker, and Node.js (see [`.nvmrc`](./events-frontend/.nvmrc))
if you want the frontend.

```bash
git clone git@github.com:enorm-labs/event-junkie.git
cd event-junkie

sdk env                                   # the right Java version
brew install pre-commit && pre-commit install    # gitleaks hook — before your first commit
./gradlew clean build                     # compile, test, lint, coverage
```

Postgres is started for you by the Gradle `bootRun` tasks via Spring Boot's Docker Compose support — there is no separate database setup step.

Full details, including the frontend, are in [docs/DEVELOPMENT.md](./docs/DEVELOPMENT.md).

## Usage

```bash
scripts/dev-env.sh up all       # importer + bff + frontend, each waited on until it answers
scripts/dev-env.sh status       # database / importer / bff / frontend
scripts/dev-env.sh down all     # add --db to stop Postgres too
```

Then open <http://localhost:5173>. Ports: frontend `5173`, BFF `8080`, importer `8081`, Postgres `56298`.

The database starts empty. To fill it:

```bash
scripts/dev-env.sh seed-all         # register all event sources (needs ijhttp)
scripts/dev-env.sh import <slug>    # import one source, polling until it settles
```

[`scripts/dev-env.sh`](./scripts/dev-env.sh) with no arguments prints the full command list, including `snapshot`,
`diff-snapshot`, `check` and `psql`.

- **Development in depth** — building, running, quality checks, dependencies: [docs/DEVELOPMENT.md](./docs/DEVELOPMENT.md)
- **Frontend** — [events-frontend/README.md](./events-frontend/README.md)
- **Working on two things at once** — [docs/WORKTREES.md](./docs/WORKTREES.md)

## Architecture

| Component                              | What it is                                                                                                                      |
| -------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------- |
| [`events-frontend`](./events-frontend) | Vue 3 SPA — the public site                                                                                                     |
| [`events-bff`](./events-bff)           | Backend-for-frontend: Kotlin, Spring Boot, WebFlux, R2DBC. Public **read** API                                                  |
| [`events-importer`](./events-importer) | Scrapers and scheduling: Kotlin, Spring Boot, Spring Modulith. **Write** side, plus an admin API that is never exposed publicly |
| [`events-core`](./events-core)         | Shared domain model consumed by both services                                                                                   |
| PostgreSQL                             | The database. Flyway migrations are owned by the importer                                                                       |

Considered and not adopted yet: Elasticsearch, a management frontend, an Android app, an MCP server. The reasoning behind the choices that _were_ made lives in
the [ADRs](./docs/adr).

## API

With a service running, Swagger UI is at:

- **events-bff** — <http://localhost:8080/webjars/swagger-ui/index.html>
- **events-importer** — <http://localhost:8081/webjars/swagger-ui/index.html>

The OpenAPI document is at `/v3/api-docs` on each port. Request files for both services live in
[`http/`](./http) and run in IntelliJ or via `ijhttp` — see
[docs/DEVELOPMENT.md](./docs/DEVELOPMENT.md#calling-the-apis).

## Documentation

**[docs/README.md](./docs/README.md) is the index** — every document, grouped by what you are trying to do. The entry points:

| Document                                                                       | What it covers                                                                                                                                                          |
| ------------------------------------------------------------------------------ | ----------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [AGENTS.md](./AGENTS.md)                                                       | **The conventions every change is held to.** Written for AI agents, but it is simply this project's conventions written down, and it is the most complete document here |
| [docs/README.md](./docs/README.md)                                             | The documentation index — start here for anything below                                                                                                                 |
| [docs/DEVELOPMENT.md](./docs/DEVELOPMENT.md)                                   | Building, running, quality checks, dependencies                                                                                                                         |
| [docs/ops/](./docs/ops)                                                        | Running the platform: setup, bootstrap, releasing, access, backups, and the restore runbook                                                                             |
| [docs/adr/](./docs/adr)                                                        | Architecture decisions, with the reasoning                                                                                                                              |
| [infra/README.md](./infra/README.md)                                           | The OpenTofu that declares that platform. Applied — both environments are live                                                                                          |
| [deploy/charts/event-junkie/README.md](./deploy/charts/event-junkie/README.md) | The Helm chart that deploys the three services onto it. Running on both environments, reconciled by Flux                                                                |
| [GitHub Issues](https://github.com/enorm-labs/event-junkie/issues)             | The backlog. `scripts/generate-backlog-snapshot.sh` renders it to `build/BACKLOG.md` for grepping                                                                       |
| [perf/README.md](./perf/README.md)                                             | Performance testing with k6                                                                                                                                             |

## Contributing

Contributions are welcome. Full guide: [CONTRIBUTING.md](./CONTRIBUTING.md). Taking part means agreeing to the
[Code of Conduct](./CODE_OF_CONDUCT.md).

**The most valuable contribution is not code.** Event data is read automatically from venue websites, so a redesigned programme page can leave us quietly wrong
for weeks. Nobody notices that faster than somebody who went to the show.

- [Wrong or missing event data](https://github.com/enorm-labs/event-junkie/issues/new?template=wrong-event-data.yml)
- [Suggest a venue](https://github.com/enorm-labs/event-junkie/issues/new?template=new-venue.yml)
- [Bug in the site or API](https://github.com/enorm-labs/event-junkie/issues/new?template=bug.yml)

Questions and product ideas go to [Discussions](https://github.com/enorm-labs/event-junkie/discussions). Security problems go
through [private disclosure](./SECURITY.md), never a public issue.

### Quick start: your first pull request

```bash
# 1. Branch from main. The name follows the Conventional Commits type and scope.
git switch main && git pull
git switch -c feat/so36-importer

# 2. Make the change. Read the relevant section of AGENTS.md first —
#    this project has strong opinions and they are all written down.

# 3. Verify. Skip the Gradle build for docs-only or frontend-only changes.
./gradlew clean build
cd events-frontend && npm run type-check && npm run lint && npm run test:unit && npm run test:e2e

# 4. Commit with a Conventional Commits subject. It drives the labels and release notes.
git commit -m "feat(importer): import events from SO36"

# 5. Push and open the PR.
git push -u origin feat/so36-importer
gh pr create --base main
```

Four things that catch people out:

- **Open an issue first** for anything beyond a small fix. Not bureaucracy — this project has a strong opinion about how importers, modules and the data model
  fit together, and a PR that cuts across it is painful to review and disheartening to receive back.
- **Rebase, never merge `main` in.** PRs are merged with "Rebase and merge", which a merge commit blocks.
- **The PR template asks about privacy and accessibility.** Both are easy to skip and expensive to miss. If your change adds a third-party request, stores
  something on the visitor's device or alters what is logged, the privacy notice needs updating in the same PR — **in both languages**.
- **Reformatting is intentional.** If `ktlintFormat` or `npm run format` rewrites a file, leave it; do not revert it as noise.

## Support

How to get help, and what to expect: [SUPPORT.md](./SUPPORT.md).

## Maintainers

Norman Lange ([@enorm-labs](https://github.com/enorm-labs)). This is a single-maintainer project — see
[SUPPORT.md](./SUPPORT.md#what-to-expect) for what that means in practice.

## License

[Apache-2.0](./LICENSE) © Norman Lange.

Contributions are accepted under the same licence. There is no CLA — opening a pull request is taken as agreeing that your contribution may be distributed under
it. Third-party dependency licences are published at
`/legal/notices` and enforced by a policy in [`config/`](./config); see
[docs/DEVELOPMENT.md](./docs/DEVELOPMENT.md#licences-and-open-source-notices).
