# Contributing to Event Junkie

Thanks for looking. This is a small project maintained by one person, so this file is short and tries to be honest about what contribution actually looks like
here rather than describing a process that does not exist.

By taking part you agree to the [Code of Conduct](./CODE_OF_CONDUCT.md).

## The most valuable thing you can do

**Tell us when the event data is wrong.** Events are read automatically from venue websites, so when a venue redesigns its programme page we can be quietly
wrong for weeks without noticing. Nobody sees that faster than someone who went to the show.

- [Wrong or missing event data](https://github.com/enorm-labs/event-junkie/issues/new?template=wrong-event-data.yml) — include the venue's own page for the
  event; that is what the importer reads.
- [Suggest a venue](https://github.com/enorm-labs/event-junkie/issues/new?template=new-venue.yml) — coverage grows one venue at a time.
- [Bug in the site or API](https://github.com/enorm-labs/event-junkie/issues/new?template=bug.yml)

Two things go **privately** instead: [security problems](./SECURITY.md), and artists or organisers asking for their name or details to be removed — you do not
need a reason and you should not have to ask in public.

Questions and product ideas belong in [Discussions](https://github.com/enorm-labs/event-junkie/discussions) rather than the issue tracker — they are
conversations rather than units of work, and one that turns out to be actionable can be converted into an issue, so nothing is lost by starting there.

## Before you write code

**Open an issue first** for anything beyond a small fix. Not bureaucracy — this project has a strong opinion about how importers, modules and the data model fit
together, and a pull request that cuts across that is painful to review and disheartening to receive back. A short conversation first saves your evening.

## Getting set up

Everything is in [docs/DEVELOPMENT.md](./docs/DEVELOPMENT.md) — JDK, Docker, `dev-env.sh`, and how to run each module. The
[README](./README.md#install) has the four-command version. The frontend has its own
[README](./events-frontend/README.md).

## Conventions

**[AGENTS.md](./AGENTS.md) is the source of truth**, and [events-frontend/AGENTS.md](./events-frontend/AGENTS.md) for the frontend. It is written for AI coding
agents, but it is simply this project's conventions written down, and it is far more complete than this file. Read the section relevant to what you are
touching. The architecture decisions behind them live in [docs/adr/](./docs/adr).

A few that catch people out:

- **Conventional Commits in the PR title.** It drives the labels and the release notes, so `feat(importer): import events from SO36`, not `Add SO36`.
- **Rebase, never merge `main` in.** PRs are merged with "Rebase and merge"; a merge commit blocks the button.
- **The version lives in `gradle.properties`** and is mirrored by hand in `events-frontend/package.json` — one bump touches both files.
- **Reformatting is intentional.** If `ktlintFormat` or `npm run format` rewrites a file, leave it; do not revert it as noise.

## Adding an importer

This is the most likely contribution, and the most structured. Read [ADR-007 Web Scraping Strategy](./docs/adr/ADR-007_WEB_SCRAPING_STRATEGY.md) in full first —
it covers selector strategy and scraping ethics, both of which matter more than the code. Then check the venue's row in
[docs/EVENT_DATA_SOURCES.md](./docs/EVENT_DATA_SOURCES.md), and copy the closest existing importer under
`events-importer/src/main/kotlin/de/norm/events/scraper/`:
a JSON feed if the venue has one (always prefer it), otherwise a single-page or two-page HTML importer.

Tests use a **captured HTML snapshot** rather than the live site, so the suite is deterministic and does not hammer a venue's server on every run.

`.github/prompts/scaffold-importer.prompt.md` walks the whole thing end to end. It is written as an agent prompt, but it reads perfectly well as a checklist.

## Before opening a pull request

```bash
./gradlew clean build          # compiles, tests, ktlint, detekt, coverage
cd events-frontend
npm run type-check && npm run lint && npm run test:unit && npm run test:e2e
```

Skip the Gradle build for documentation-only or frontend-only changes — it covers the backend modules only.

The pull request template asks two questions that are easy to skip and expensive to miss:

- **Privacy.** Does the change add a third-party request, store something on the visitor's device, or alter what is logged or which provider processes it? If
  so, the privacy notice needs updating in the same PR — **both language versions**
  ([English](./events-frontend/src/views/legal/PrivacyView.en.vue), [German](./events-frontend/src/views/legal/PrivacyView.de.vue)). The list of triggers is in
  [AGENTS.md](./AGENTS.md#privacy--gdpr--re-check-when-infrastructure-or-features-change) — they rarely look like privacy work.
- **Accessibility.** The project targets WCAG 2.1 AA, and both the linter and an axe sweep enforce it. Do not disable a rule to go green.

## Licensing

Contributions are accepted under the [Apache License 2.0](./LICENSE), the same licence the project is released under. There is no CLA — opening a pull request
is taken as agreeing that your contribution may be distributed under that licence.

If you add a dependency, its licence has to clear the policy in [config/](./config): permissive or weak copyleft. AGPL, GPL without the Classpath Exception, and
source-available licences (SSPL, BUSL, Elastic) are not acceptable for a public network service — see the
[development guide](./docs/DEVELOPMENT.md#licences-and-open-source-notices).

## Built with AI

Most of the code here was written by AI coding agents working from the prompts in [`.github/prompts/`](./.github/prompts) and the conventions in `AGENTS.md`.
You are welcome to work the same way, and equally welcome not to. Either way the standard is the same: you are responsible for what you open a PR with, and
"the agent wrote it" is not an answer to a review comment.
