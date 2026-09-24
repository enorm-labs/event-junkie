<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="./docs/branding/readme-logo-dark.png" />
    <img alt="Event Junkie — can't get enough of Berlin" src="./docs/branding/readme-logo-light.png" width="440" />
  </picture>
</p>

# Event Junkie

[![Build & Test Backend](https://github.com/enorm-labs/event-junkie/actions/workflows/build-backend.yml/badge.svg)](https://github.com/enorm-labs/event-junkie/actions/workflows/build-backend.yml)
[![Build & Test Frontend](https://github.com/enorm-labs/event-junkie/actions/workflows/build-frontend.yml/badge.svg)](https://github.com/enorm-labs/event-junkie/actions/workflows/build-frontend.yml)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](./LICENSE)
[![Status](https://img.shields.io/badge/Status-Beta-orange.svg)](#status)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.4.10-7F52FF.svg?logo=kotlin&logoColor=white)](https://kotlinlang.org)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F.svg?logo=springboot&logoColor=white)](https://spring.io/projects/spring-boot)
[![Java](https://img.shields.io/badge/Java-25-ED8B00.svg?logo=openjdk&logoColor=white)](https://openjdk.org)
[![Vue.js](https://img.shields.io/badge/Vue.js-3-4FC08D.svg?logo=vuedotjs&logoColor=white)](https://vuejs.org)

One filterable feed of what is on across Berlin's venues, collected automatically from their own websites. Live at <https://event-junkie.de>.

> **The event app Berlin deserves.**

<p align="center">
  <img alt="The events list: a filter bar over a grid of Berlin events, each with its poster, venue, time and genre tags" src="./docs/screenshots/events-dark.png" width="900" />
</p>

<p align="center">
  <sub>The events list, in the dark theme new visitors get by default — real data, scraped from the venues' own sites. September 2026; see <a href="./docs/screenshots/">docs/screenshots</a>.</sub>
</p>

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

Berlin's scene is huge and scattered. What's on lives across dozens of venue and promoter websites, each with its own layout and its own gaps, so answering
something as ordinary as _what's on near me this weekend, in my genre, that I can afford?_ means a dozen browser tabs and a lot of guessing. I did exactly that
for years, working through my favourite venues' sites by hand, one after the other.

The existing options each solve a slice of it. Resident Advisor is excellent at electronic music and only that. Bandsintown and Songkick follow _artists_, which
is no help when you want to know what is happening on Thursday. Ticketing sites list what they sell, which quietly excludes free entry, door-only nights and the
small rooms.

Event Junkie is the thing none of them tries to be: **one feed for all of it**, every kind of venue, every genre, free and ticketed alike, always linking back to
the venue's own page for tickets and the final word.

### Why Berlin

Berlin is one of the best cities on this planet: not always clean, more than a little mad, poor but sexy, and a place where you can live freely and be the
person you actually are. The club culture that grew out of that exists nowhere else in the same form. Since 2024,
[Berlin's techno culture](https://www.unesco.de/staette/technokultur-in-berlin/) has been listed in Germany's nationwide inventory of intangible cultural
heritage. That inventory holds living practices rather than buildings, and living things can disappear.

Which is what the [Clubcommission](https://www.clubcommission.de/), the association behind Berlin's club culture, calls the
[Clubsterben](https://www.clubcommission.de/pressemitteilung-clubsterben-ist-wieder-an-der-tagesordnung/): rents going up, spaces going away, rooms closing
after decades. A venue nobody can find any more is not the biggest part of that. It is the part this project can do something about, and if this site puts a few
people in a small room they had never heard of, it has done its job.

Does it help? Does anyone need it? I don't know. I want to try.

The scope rule, in one line: **if a Berlin venue puts it on a stage in the evening, it is in scope.** What that includes, what is deliberately excluded and
which coverage questions are still open is in
[EVENT_SCOPE.md](./docs/EVENT_SCOPE.md). What the product is and does today is in
[PRODUCT_OVERVIEW.md](./docs/PRODUCT_OVERVIEW.md); where it is headed is in
[VISION_ROADMAP_IDEAS.md](./docs/VISION_ROADMAP_IDEAS.md).

## Status

🚧 **Public beta.** The site is live at <https://event-junkie.de>.

87 Berlin sources are imported on a schedule. Production and staging run on Hetzner and are reconciled by Flux; staging has no public address. The site
carries a `beta` badge until `1.0.0`, because coverage is incomplete and some details can be stale. `1.0.0` comes when the criteria on
[#295](https://github.com/enorm-labs/event-junkie/issues/295) hold.

## Built with AI

Most of the code in this repository was written by AI coding agents (primarily
[Claude Code](https://claude.com/claude-code)), working from the prompts and skills in
[`.github/prompts/`](./.github/prompts) and the conventions in [AGENTS.md](./AGENTS.md).

Two third-party skills are vendored into [`.claude/skills/`](./.claude/skills) rather than left to each contributor's global install:
[`gh`](./.claude/skills/gh/SKILL.md) from [`cli/cli`](https://github.com/cli/cli/tree/trunk/skills/gh), for driving the GitHub CLI, and
[`asd-ste100`](./.claude/skills/asd-ste100/SKILL.md), for the Simplified Technical English the documentation is written in. Each carries a `VENDORED.md` with
its upstream commit and the command that refreshes it.

The vision, the product ideas, the architecture decisions and the priorities are mine. The agents implement against them; every change goes through review
before it lands.

This is also _why_ the project exists in this form. I wanted to build it for years and started several times, and as a hobby project alongside everything else
every attempt was too much work for one person. AI agents are what made it possible. A real application with real constraints is then also where you find out what this way of working is good at, and where it
still needs a human paying attention.

## Install

Prerequisites: a JDK (see [`.sdkmanrc`](./.sdkmanrc), managed with [SDKMAN](https://sdkman.io/)), Docker, the
[GitHub CLI](https://cli.github.com/), and Node.js 24.15 or later (see [`.nvmrc`](./events-frontend/.nvmrc)) if you want the frontend.

```bash
git clone git@github.com:enorm-labs/event-junkie.git
cd event-junkie

sdk env                                   # the right Java version
brew install pre-commit && pre-commit install    # gitleaks hook — before your first commit
brew install gh && gh auth login          # the agent prompts drive GitHub through it
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

**In a picture:** [docs/ops/PLATFORM_SETUP.md §1](./docs/ops/PLATFORM_SETUP.md#1-what-runs-where) — the trust boundaries, the request path, the deploy path and
the objects the chart creates. [docs/architecture/](./docs/architecture) holds the generated inventory that fails CI when those diagrams stop matching what is
deployed.

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

- [Wrong or missing event data](https://github.com/enorm-labs/event-junkie/issues/new?template=1-wrong-event-data.yml)
- [Suggest a venue](https://github.com/enorm-labs/event-junkie/issues/new?template=3-new-venue.yml)
- [Bug in the site or API](https://github.com/enorm-labs/event-junkie/issues/new?template=2-bug.yml)

Questions and product ideas go to [Discussions](https://github.com/enorm-labs/event-junkie/discussions). Security problems go
through [private disclosure](./SECURITY.md), never a public issue.

### Quick start: your first pull request

```bash
# 1. Branch from main — in your fork, if you do not have push access (CONTRIBUTING.md has the fork flow).
#    The name follows the Conventional Commits type and scope.
git switch main && git pull
git switch -c feat/so36-importer

# 2. Make the change. Read the relevant section of AGENTS.md first —
#    this project has strong opinions and they are all written down.

# 3. Verify. Skip the Gradle build for docs-only or frontend-only changes.
./gradlew ktlintCheck detekt detektMain detektTest build koverLog -PwarningsAsErrors
cd events-frontend && npm run type-check && npm run lint && npm run test:unit -- --run && npm run test:e2e -- --project=chromium

# 4. Commit with a Conventional Commits subject. It drives the labels and release notes.
git commit -m "feat(importer): import events from SO36"

# 5. Push and open the PR. "Closes #<n>" in the body links and later closes the issue.
git push -u origin feat/so36-importer
gh pr create --base main
```

Five things that catch people out:

- **Open an issue first** for anything beyond a small fix. Not bureaucracy — this project has a strong opinion about how importers, modules and the data model
  fit together, and a PR that cuts across it is painful to review and disheartening to receive back.
- **Rebase, never merge `main` in.** PRs are merged with "Rebase and merge", which a merge commit blocks.
- **One commit per PR.** "Rebase and merge" replays every commit as written, so fold review fixes into the commit with `git commit --amend` rather than
  stacking "fix the lint" on top.
- **The PR template asks about privacy and accessibility.** Both are easy to skip and expensive to miss. If your change adds a third-party request, stores
  something on the visitor's device or alters what is logged, the privacy notice needs updating in the same PR — **in both languages**.
- **Reformatting is intentional.** If `ktlintFormat` or `npm run format` rewrites a file, leave it; do not revert it as noise.

## Support

How to get help, and what to expect: [SUPPORT.md](./SUPPORT.md).

## Maintainers

Norman Lange ([@enorm](https://github.com/enorm)), publishing as [enorm-labs](https://github.com/enorm-labs). This is a single-maintainer project — see
[SUPPORT.md](./SUPPORT.md#what-to-expect) for what that means in practice.

## License

[Apache-2.0](./LICENSE) © Norman Lange.

Contributions are accepted under the same licence. There is no CLA — opening a pull request is taken as agreeing that your contribution may be distributed under
it. Third-party dependency licences are published at
`/legal/notices` and enforced by a policy in [`config/`](./config); see
[docs/DEVELOPMENT.md](./docs/DEVELOPMENT.md#licences-and-open-source-notices).
