# Contributing to Event Junkie

Thanks for looking. This is a small project maintained by one person, so this file is short and tries to be honest about what contribution actually looks like
here rather than describing a process that does not exist.

By taking part you agree to the [Code of Conduct](./CODE_OF_CONDUCT.md).

## The most valuable thing you can do

**Tell us when the event data is wrong.** Events are read automatically from venue websites, so when a venue redesigns its programme page we can be quietly
wrong for weeks without noticing. Nobody sees that faster than someone who went to the show.

- [Wrong or missing event data](https://github.com/enorm-labs/event-junkie/issues/new?template=1-wrong-event-data.yml) — include the venue's own page for the
  event; that is what the importer reads.
- [Suggest a venue](https://github.com/enorm-labs/event-junkie/issues/new?template=3-new-venue.yml) — coverage grows one venue at a time.
- [Bug in the site or API](https://github.com/enorm-labs/event-junkie/issues/new?template=2-bug.yml)

Two things go **privately** instead: [security problems](./SECURITY.md), and artists or organisers asking for their name or details to be removed — you do not
need a reason and you should not have to ask in public.

Questions and product ideas belong in [Discussions](https://github.com/enorm-labs/event-junkie/discussions) rather than the issue tracker — they are
conversations rather than units of work, and one that turns out to be actionable can be converted into an issue, so nothing is lost by starting there.

## Before you write code

**Open an issue first** for anything beyond a small fix. Not bureaucracy — this project has a strong opinion about how importers, modules and the data model fit
together, and a pull request that cuts across that is painful to review and disheartening to receive back. A short conversation first saves your evening.

Pick whichever form fits; the forms set the labels and the issue type, so there is nothing to add by hand. Issues labelled
[`good first issue`](https://github.com/enorm-labs/event-junkie/labels/good%20first%20issue) are the ones picked out as a sensible place to start.

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
- **One commit per pull request.** "Rebase and merge" replays every commit on the branch onto `main` exactly as written, so three "fix the lint" commits
  become three commits on `main` for good. Fold review fixes into the commit (`git commit --amend`, then `git push --force-with-lease`) and keep the commit
  message, the PR title and the PR description saying the same thing.
- **`Closes #<n>` in the PR body**, on its own line, when the change finishes an issue. Merging then closes the issue and moves it on the project board.
- **Leave the version alone.** It lives in `gradle.properties` and is mirrored into three other files by `scripts/version.sh`, which the release workflow
  drives. A pull request never bumps it.
- **Reformatting is intentional.** If `ktlintFormat` or `npm run format` rewrites a file, leave it; do not revert it as noise.

## Adding an importer

This is the most likely contribution, and the most structured. Read [ADR-007 Web Scraping Strategy](./docs/adr/ADR-007_WEB_SCRAPING_STRATEGY.md) in full first —
it covers selector strategy and scraping ethics, both of which matter more than the code. Then check the venue's row in
[docs/EVENT_DATA_SOURCES.md](./docs/EVENT_DATA_SOURCES.md), and copy the closest existing importer under
`events-importer/src/main/kotlin/de/norm/events/scraper/`:
a JSON feed if the venue has one (always prefer it), otherwise a single-page or two-page HTML importer.

Tests use a **captured HTML snapshot** rather than the live site, so the suite is deterministic and does not hammer a venue's server on every run.

`.github/prompts/scaffold-importer.prompt.md` walks the whole thing end to end. It is written as an agent prompt, but it reads perfectly well as a checklist.

## Opening a pull request from a fork

You do not have push access to this repository, and you do not need it. **Fork, branch, push to your
fork, open the pull request from there** — the ordinary GitHub flow:

```bash
gh repo fork enorm-labs/event-junkie --clone     # or fork in the UI and clone your copy
cd event-junkie
git switch -c fix/opening-hours-parsing
# ... your change, then:
git push -u origin fix/opening-hours-parsing
gh pr create --repo enorm-labs/event-junkie --title "fix(importer): parse opening hours without a year"
```

Keep the branch rebased on `main` rather than merging `main` into it — pull requests here are merged
with "Rebase and merge", and a merge commit blocks the button.

### What CI will and will not run on your pull request

Worth knowing before a red check makes you think you broke something. **Fourteen checks are required**,
and all fourteen run on a fork's pull request exactly as they do on ours — none of them needs a secret
or a token that can write:

```
Build & Test (backend | frontend)                   Gradle, and the Vite + Playwright matrix
Lint & render · ShellCheck deploy-story scripts     the Helm chart
Lint & audit workflows                              actionlint + zizmor
Format & Validate (infra/bootstrap | staging | production)
ShellCheck cloud-init
Analyze (actions | javascript-typescript | java-kotlin)    CodeQL
Dependency Review
Check the notices against the dependencies          the open-source notices file is current
```

That was verified rather than assumed, on 2026-08-19, by opening the repository's first fork pull
request and merging it (#579).

Three things behave differently on a fork, and none of them means anything is wrong:

- **The coverage comment does not appear, and detekt findings do not reach the Security tab.** A
  fork's `GITHUB_TOKEN` is read-only, so nothing can post a comment and nothing can write to Code
  Scanning. Every one of those steps is skipped rather than failed, which is deliberate — an
  unguarded upload would answer `403` and turn your pull request red for something you did not
  cause. Both checks still _run_: `./gradlew build` runs `koverVerify` and detekt still fails the
  build on a violation. What is lost is the reporting, so the per-changed-file coverage threshold
  and the detekt annotations are a review matter on this path.
- **CodeQL findings do not reach the Security tab either, and the checks still pass.** The three
  `Analyze (…)` jobs run the full analysis on your pull request; only the upload is skipped, because
  writing to Code Scanning needs a token a fork does not get. This is why CodeQL runs here as a
  workflow rather than through GitHub's default setup — default setup produces no run at all for a
  fork, and a required check that never reports leaves a pull request unmergeable with nothing red to
  explain why. That was this repository's own bug until #479 found it.
- **A documentation-only pull request does not pay for `Build & Test`.** Each of the two workflows
  decides from your changed files whether there is anything to build; if there is not, the build is
  skipped and the check reports green anyway. So a README typo does not sit through sixteen minutes
  of Gradle and Playwright, and a change that touches code cannot merge without them.

**One secret is referenced on a pull request, and it is empty on yours.** `NVD_API_KEY` is passed to
the informational OWASP Dependency-Check job in `build-backend.yml`, and GitHub does not expose
secrets to a run triggered from a fork. The scan then falls back to unauthenticated NVD access,
which is rate limited enough that it often does not finish — so it is marked `continue-on-error` and
cannot fail your pull request either way. The authoritative dependency scan is the nightly one,
which no pull request triggers. Nothing else here reads a secret on a pull request.

## Before opening a pull request

```bash
./gradlew ktlintCheck detekt build koverLog      # compiles, tests, ktlint, detekt, coverage
cd events-frontend
npm run type-check && npm run lint && npm run test:unit -- --run && npm run test:e2e -- --project=chromium
cd ..
scripts/comment-lint.sh check                    # the comment rules detekt and ESLint cannot see
scripts/format-markdown.sh check                 # only when you touched a .md file
```

Skip the Gradle build for documentation-only or frontend-only changes — it covers the backend modules only. Documentation is not check-free, though: `.md`
files are formatted by `scripts/format-markdown.sh`, which the `pre-commit` hook runs for you (see
[docs/DEVELOPMENT.md](./docs/DEVELOPMENT.md#markdown-formatting)), and anything under `docs/` is held to a sentence-length ceiling by `scripts/ste-lint.sh check`.

That is the short list. The full one, including what to run when a change touches `infra/`, `deploy/` or a dependency file, is
[`.github/prompts/verify.prompt.md`](./.github/prompts/verify.prompt.md) — CI runs the same sequence, so a green run there is a green pull request.

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
