# Scripts

Everything in this directory, in one table per audience, with what runs it. `scripts/index-parity.sh` fails when a file here has no row, when a row names a
file that does not exist, when anything in the tree references a `scripts/<name>` that does not exist, or when a script does not answer `--help`.

## The short version

- **Three audiences, one directory.** A _gate_ runs in CI or a commit hook and fails a build. A _tool_ is what an agent or a developer runs, with no cluster in
  reach. An _ops_ script is what the operator runs, with the tunnel up. The table says which; the name does not.
- **`--help` prints the header's `Usage:` block.** Every script here answers `-h` or `--help` with exit 0, before it touches a tool, a file or the network. The
  block is the lines from `# Usage:` to the next blank comment line, so it holds what a caller types and nothing else. Reasoning goes above it. The idiom is one
  `case` arm, the same in every file; `dev-env.sh` is the shortest example. The exception is `shell-aliases.sh`, which is sourced and defines functions.
- **A header is the documentation.** Name, one-line purpose, `Usage:`, then what it requires and what it reaches. The rows below quote those headers.
- **Python is standard library only, with `argparse`.** `ste_lint.py` and the `deploy/dashboards/` and `deploy/alerts/` scripts are copied to a node and run
  there, where nothing is installed. `outline_text.py` is the one exception, and its wrapper builds the venv it needs. ruff at a pinned
  version is the gate, in the commit hook and in `validate-python.yml`, from the root `ruff.toml`.

## What was decided against

- **Subdirectories.** 158 files reference a `scripts/<name>` path: 17 workflows with `paths:` filters, the commit hook's `files:` patterns, AGENTS.md, every
  prompt under `.github/prompts/` and the runbooks. Moving a script is a tree-wide rename with no behaviour change, and `validate-scripts.yml` and
  `validate-chart.yml` lint four of these twice on purpose, an arrangement tied to paths and to the `main` ruleset. Grouping happens here, and by prefix:
  `*-parity.sh` compares two copies of one fact, `*-test.sh` asserts what its subject decides.
- **A shell framework** — bashly, `just`, a Makefile. Each adds a tool to install and a second layer over scripts that already take verbs. ShellCheck at a
  pinned version is the gate, in the commit hook and in CI.
- **A Python CLI library** — click, typer. See the standard-library rule above.
- **bats.** Five `*-test.sh` files already run in CI; a rewrite buys a dependency.

## Gates

Run by CI, by the commit hook, or by `/verify`. Each fails a build.

| Script                                             | What it answers                                                                                                                            | Runs in                                                                      |
| -------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------ | ---------------------------------------------------------------------------- |
| `cluster-assertions.sh`                            | The half of the deploy gate a chart test suite cannot be: what each cluster's HelmRelease deploys                                          | `validate-chart.yml`, `release.yml`, `/verify`                               |
| `collector-parity.sh`                              | One log field name, four places, and nothing else joining them                                                                             | `validate-docs.yml`, `/verify`                                               |
| `comment-lint.sh`                                  | The comment rules, for the languages detekt and ESLint cannot see                                                                          | `validate-comments.yml`, `/verify`, `/compact-comments`                      |
| `comment-lint-test.sh`                             | Asserts what `comment-lint.sh` counts, not merely that it runs                                                                             | `validate-comments.yml`                                                      |
| `comment-density.sh`                               | How much of the repository is comments, and where that volume sits. A diagnostic, not a gate                                               | `validate-comments.yml`, `/compact-comments`, `/code-review`                 |
| `comment-density-test.sh`                          | Asserts what `comment-density.sh` counts                                                                                                   | `validate-comments.yml`                                                      |
| `csp-parity.sh`                                    | The Content-Security-Policy exists twice, and the two copies must agree                                                                    | `validate-chart.yml`, `/verify`                                              |
| `format-markdown.sh`                               | oxfmt over the repository's Markdown, and only its Markdown — the pinned binary, never one on `$PATH`                                      | `validate-docs.yml`, hook `format-markdown`, `/verify`                       |
| `index-parity.sh`                                  | This index, the directory and every `scripts/` reference in the tree agree; every script answers `--help`                                  | `validate-scripts.yml`, `validate-docs.yml`, hook `index-parity`, `/verify`  |
| `dashboard-parity.sh` + `links_export.py`          | The operations page's generated files — `links.js`, the bookmarks file, `dashboard.css` — against LINKS.md, DAILY_COMMANDS.md and the page | `validate-docs.yml`, hook `dashboard-parity`, `/verify`                      |
| `notices-parity.sh`                                | The committed open-source notices, against what the dependencies say                                                                       | `validate-notices.yml`, `fix-notices-on-bot-prs.yml`, `/verify`              |
| `rules-parity.sh`                                  | One rule file, two agents, and no second copy of anything                                                                                  | `validate-docs.yml`, hook `rules-parity`, `/verify`                          |
| `scan-coverage.sh` + `scan-coverage-baseline.txt`  | How much a scanner looked at, not only what it found — denominators, not exit codes (#1087)                                                | `build-backend.yml`, `validate-chart.yml`, `validate-workflows.yml`, nightly |
| `scan-coverage-test.sh`                            | Asserts what `scan-coverage.sh` decides                                                                                                    | `validate-scripts.yml`                                                       |
| `scope-parity.sh`                                  | One list of product scopes, nine places; `label-pr.yml` is the copy the rest must match                                                    | `validate-docs.yml`, `/verify`, hook `scope-parity`                          |
| `secrets-parity.sh`                                | The number of cluster secrets SECRETS.md states, against the rows it lists                                                                 | `validate-docs.yml`, `/verify`                                               |
| `skill-parity.sh`                                  | Every skill is a command, every command is a skill, and both point somewhere                                                               | `validate-docs.yml`, hook `skill-parity`, `/verify`                          |
| `ste-lint.sh` + `ste_lint.py` + `ste-baseline.txt` | The sentence rules for `docs/`, against a per-area ceiling that only moves down (#733)                                                     | `validate-docs.yml`, `/verify`, `/write-adr`, `/update-docs`                 |
| `uid-consistency.sh`                               | The chart and the three images agree about the UID they run as, above the 10000 floor                                                      | `validate-chart.yml`                                                         |
| `version.sh`                                       | The one place that knows what version this commit is, read from the release tags and the commits since the last one (ADR-032)              | `release.yml`, `cut-release.yml`, both build workflows                       |
| `version-test.sh`                                  | Snapshot versions ORDER, asserted against Helm's own solver — a format check would not catch #455                                          | `validate-chart.yml`, `validate-scripts.yml`, `/verify`                      |
| `version-deserved-test.sh`                         | Asserts what `version.sh deserved` decides: the number the commits since the last release earn, product `feat` only                        | `validate-scripts.yml`                                                       |
| `release-highlights.sh`                            | The fallback summary on top of a release's notes, from the commits' subjects, when `/release-highlights` wrote none                        | `cut-release.yml`                                                            |
| `release-highlights-test.sh`                       | Asserts what `release-highlights.sh` summarises, and what it leaves to the label categories                                                | `validate-scripts.yml`                                                       |

## Tools

Run by an agent or a developer. No cluster, no tunnel; `k3d-rehearsal.sh` makes its own.

| Script                                | What it does                                                                                                      | Used by                                                 |
| ------------------------------------- | ----------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| `dev-env.sh`                          | Local dev environment control: start and stop the stack, seed sources, trigger imports, inspect and diff the data | `/importer-smoke`, `/next-importer`, README.md          |
| `k3d-rehearsal.sh`                    | The whole stack on a local Kubernetes, proven end to end, then torn down                                          | `/k3d-rehearsal`                                        |
| `issue-board.sh`                      | Read and set an issue's Status and Priority on the project board — fields, not labels                             | `/start-issue`, `/new-issue`, `/open-pr`, two workflows |
| `generate-backlog-snapshot.sh`        | Every open issue rendered into `build/BACKLOG.md`, for grepping instead of a network round trip                   | `/new-issue`, `/next-issue`, `/milestone-plan`          |
| `geocode-venues.py`                   | Venue addresses to coordinates with the Google Geocoding API, and an audit of the 86 seeded ones (#357)           | `/scaffold-importer`                                    |
| `outline-text.sh` + `outline_text.py` | A string set in a font, printed as an SVG path — brand artwork carries outlined glyphs, never `<text>`            | `docs/branding/`                                        |

## Ops

Run by the operator, with the WireGuard tunnel up. Three of these live next to what they operate rather than here, and are listed so there is one place to
look. None of them wraps `tofu`, `helm upgrade`, or anything that writes to production.

| Script                       | What it does                                                                                                                    | Where it is documented                                  |
| ---------------------------- | ------------------------------------------------------------------------------------------------------------------------------- | ------------------------------------------------------- |
| `ej.sh`                      | The operator's session: the tunnel and the three port-forwards, up in one command and down in one; also `status` and `versions` | `docs/ops/DAILY_COMMANDS.md` § The short version        |
| `shell-aliases.sh`           | Shell functions for day-to-day work against the two clusters; source it from `~/.zshrc`                                         | `docs/ops/DAILY_COMMANDS.md` § Aliases                  |
| `deployed-versions.sh`       | Which published chart version each cluster would resolve, right now — Flux's own selection, no cluster needed                   | `image-scan-scheduled.yml`, `docs/ops/RELEASING.md`     |
| `upstream-node-pins.sh`      | Are the k3s and wal-g pins behind upstream? `node-pin-reminder.yml` runs it weekly                                              | `docs/ops/K3S_UPGRADE.md`, `docs/ops/BACKUPS.md`        |
| `seed-sources.py`            | Register the venues and event sources from `dev-seed.http` on a cluster; the dry run is the drift report (#876)                 | `docs/ops/CLUSTER_ACCESS.md` § 6a                       |
| `apply-licence-review.py`    | Write the licence review in `docs/licence-review/` onto the event sources; dry run by default (#283)                            | `docs/licence-review/`                                  |
| `venue-images.py`            | Write the reviewed images in `docs/venue-images/` onto the venues, from Commons or Flickr; dry run by default (#1277)           | `docs/venue-images/`                                    |
| `openverse-search.py`        | Search Openverse for a photograph of a venue that has none; proposes candidates, decides nothing (#1285)                        | `docs/venue-images/`                                    |
| `musicbrainz-match.py`       | Match every artist row against MusicBrainz under one written match rule and count the outcome; decides nothing (#1549)          | `docs/adr/ADR-031_ARTIST_IDENTITY_HUB.md`               |
| `discogs-match.py`           | Match every artist row against Discogs under the same rule, MusicBrainz's `none` rows first, and cross-table the two (#1549)    | `docs/adr/ADR-031_ARTIST_IDENTITY_HUB.md`               |
| `promoter-duplicates.py`     | Group the promoter rows that are probably one promoter, with the event count behind each; decides nothing (#328)                | `temp/328-enrich-promoters.md`                          |
| `promoter-websites.py`       | Write the reviewed websites and descriptions in `docs/promoters/` onto the promoters; dry run by default (#328)                 | `docs/promoters/`                                       |
| `mail-probe.py`              | Send to a role mailbox, wait for it to arrive over IMAP, then ping healthchecks.io; run daily by `mail-probe.yml` (#637)        | `docs/ops/EMAIL.md` §7a                                 |
| `cluster-state.sh`           | One read-only picture of an environment: nodes, Flux, the hand-made secrets, certificates, data and backups (#560)              | `docs/ops/DAILY_COMMANDS.md`, `infra/README.md`         |
| `restore-drill.sh`           | Runs the restore drill on the database node: replay from the bucket, PITR past a drop, cleanup, timings (#862)                  | `docs/ops/RESTORE_RUNBOOK.md`, `docs/ops/BACKUPS.md` §9 |
| `deploy/dashboards/apply.sh` | Import the OpenObserve dashboards, and check their panels return data                                                           | `docs/ops/OPENOBSERVE.md`                               |
| `deploy/alerts/apply.sh`     | Push the alert rules into OpenObserve, or check that they can fire at all                                                       | `docs/ops/OPENOBSERVE.md`, `docs/ops/DAILY_COMMANDS.md` |
| `infra/check-capacity.sh`    | Can Hetzner deliver the server the plan orders? Only `--probe` answers                                                          | `infra/README.md`, `docs/ops/DAILY_COMMANDS.md`         |
| `infra/check_user_data.py`   | Render both cloud-init roles with sample values and measure them against Hetzner's 32 KiB `user_data` cap (#1482)               | `infra/AGENTS.md`                                       |
