# ADR-032: The version is computed from the tags and the commits, and no file in the tree carries it

## Status

**Accepted (2026-09-20) — no file in the tree carries the version. A build computes its number from the last release tag and the commits since it, by
the rule of ADR-025. A release is the tag `cut-release.yml` writes from that same rule. The bump pull request after a cut and the raise pull request before
one no longer exist.**

**Not yet implemented.** The implementation is the pull request that closes [#1614](https://github.com/enorm-labs/event-junkie/issues/1614). Until it merges,
`gradle.properties` is still the source of truth and [DEVELOPMENT.md §Versions](../DEVELOPMENT.md#versions-and-cutting-a-release) still describes the tree.

**Supersedes one constraint of [ADR-025](ADR-025_RELEASE_VERSION_FROM_COMMITS.md)**: "the tag must equal `gradle.properties`, so a release number is never
typed". The number is still never typed. It is computed twice from the same commits, once for the snapshot and once for the tag. The tag build refuses a tag that
disagrees with the computation. ADR-025's rule for how far the number moves stands unchanged, and so does its refusal of a red snapshot.
[ADR-016](ADR-016_GITOPS_DELIVERY.md) is untouched: a snapshot is still a prerelease of the number it is heading for, and snapshots still order by timestamp.

## Context

Since ADR-025 landed on 2026-09-05, `main` took 354 commits and cut 30 releases. 49 of those commits are version bookkeeping: 31
`chore(release): open the next development version` after a cut and 18 `chore(release): raise the coming release` before one. Each is a pull request opened by
the release App, a CI cycle, and an approval by the one person who approves. The raise run ends red by design, so a cut that needed one is two dispatches with
a wait between them. ADR-025 wrote "when the two-dispatch cut annoys for the third time" as its revisit trigger. It has.

The number in the tree exists for one reason, the first constraint ADR-025 inherited. The tag had to equal `gradle.properties`, so that a release number is
never typed by a person. That constraint was written when a person chose the number. `scripts/version.sh deserved` now reads it from the commits. The tree no
longer holds the truth. It holds a copy of what `deserved` said last time, and the two pull requests are what keep the copy current.

The cost is also on the clusters. Staging follows the newest snapshot, and `X.Y.Z-snapshot.*` sorts below `X.Y.Z`. After a cut, no merge to `main` reaches
staging until the bump pull request merges, because every snapshot is still named after the released number. Three memory notes in the agent's store describe
this trap and the two others the pull requests cause. All three go away with the pull requests.

**The constraints any candidate had to satisfy:**

| Constraint                                                                 | Fixed by                                                                                         |
| -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------ |
| A release number is never typed                                            | `cut-release.yml` writes the tag from `scripts/version.sh deserved`, and the tag build checks it |
| A release is a rebuild of a green snapshot of the same commit              | `cut-release.yml` refuses a commit whose snapshot publish is red ([#1117][1117])                 |
| Snapshots must order, and a snapshot is a prerelease of the coming release | ADR-016 and the timestamped scheme ([#455][455])                                                 |
| Nothing in CI pushes to `main`                                             | the `main` ruleset, which now has nothing to push                                                |
| The rule is checkable by a script and by a person, with one answer         | AGENTS.md, which puts version logic in `scripts/version.sh` and nowhere else                     |

The first constraint is the one that changes shape. It used to be satisfied by a file that the tag had to match. It is now satisfied by computing the number
from the history at the tag. That history is the same one the snapshot was computed from.

## Candidate options

1. **Keep the tree as the source.** Two pull requests and two dispatches per minor cycle. Nothing to build, and the cost stays at 14 % of the commits on
   `main`.
2. **ADR-025 option 3: raise the tree the moment a `feat` merges.** A workflow on every push to `main` opens the raise pull request as soon as the commits
   deserve more. It removes the second dispatch and keeps the raise pull request and its approval. It adds a workflow and an App token mint on every push.
   Its pull request can race the bump pull request the cut opens.
3. **Bump in the pull request that earns it.** A required check compares `deserved` over `last tag..PR head` with the number on the PR head. A `feat` pull
   request carries `scripts/version.sh bump minor`, and later ones pass because the tree already says so. Two `feat` pull requests in flight make identical
   hunks, which rebase cleanly. It removes the raise pull request. It keeps the bump pull request after every cut, and staging stays pinned to the release
   until that merges.
4. **No version in the tree.** On a branch, `scripts/version.sh compute` returns `deserved` with the snapshot suffix. On a tag, it returns the
   tag's number after checking it against `deserved`. `cut-release.yml` tags what `deserved` says and has no tree to compare with. `-Pversion` already
   stamps the Gradle build in `release.yml`. `Chart.yaml` is already a placeholder the workflow stamps over. `package.json`'s number is decorative,
   because the site reads `GET /meta` ([LEGAL.md §4](../LEGAL.md)). Every version change in the tree, and every check that kept four files equal, is deleted.

Decided against without a section: release-please and semantic-release, for the reasons ADR-025 gave. Also promoting the snapshot images to the release
tag instead of rebuilding them. The version is baked into the image and into `/meta` at build time, so a promoted image would report `-snapshot`.

## Comparison

| Axis                                      | 1. Keep    | 2. Auto-raise | 3. Bump in the PR  | 4. No version in the tree |
| ----------------------------------------- | ---------- | ------------- | ------------------ | ------------------------- |
| Pull requests per minor cycle             | 2          | 2             | 1                  | 0                         |
| Dispatches per minor cycle                | 2          | 1             | 1                  | 1                         |
| Staging pinned to the release after a cut | yes        | yes           | yes                | no                        |
| New workflow or check                     | none       | one workflow  | one required check | none                      |
| Files that name the version               | 4          | 4             | 4                  | 0                         |
| Number computed in                        | tree + cut | tree + cut    | tree + PR check    | one script, once          |
| Needs `fetch-depth: 0` on the build       | cut only   | cut and push  | cut and PR         | every build               |

## Decision

Option 4. The number lives in the history and in one script that reads it. Nothing in the tree repeats it.

- `scripts/version.sh compute` on a branch returns `<deserved>-snapshot.<committer-timestamp>.g<sha>`. `deserved` is ADR-025's rule over the commits since the
  last reachable release tag. It is a patch when nothing earned more, and a minor the moment a product `feat` lands.
- `scripts/version.sh compute` on `refs/tags/vX.Y.Z` returns `X.Y.Z`, after checking that `X.Y.Z` equals `deserved` at that commit. A tag that disagrees is
  refused, which is what "never typed" now means.
- `cut-release.yml` computes `deserved`, applies the `at_least` floor, refuses a red snapshot and an existing tag, and publishes the release. It opens no pull
  request and ends green.
- `gradle.properties`, `events-frontend/package.json` and `Chart.yaml` hold `0.0.0` placeholders that the build overrides. A build with no release tag in
  reach reports `0.0.0-local`.
- `scripts/version.sh set`, `bump`, `next` and `check` are deleted, and so is the `version consistency` pre-commit hook.

What settled it was the second constraint, not the first. A release is a rebuild of a green snapshot of the same commit. The snapshot's number was computed
from that commit's history, and the tag's number is computed from the same history. Two computations of one input agree without a file between them. The
file was the thing that could disagree, and every pull request existed to stop it from doing so.

## Consequences

- **Every build that computes a version needs the tags.** `release.yml`, `build-backend.yml` and `build-frontend.yml` check out with `fetch-depth: 0`. A
  shallow clone somewhere else that calls `deserved` dies with "no release tag", which is the right failure. `compute` degrades to `0.0.0-local` instead, so
  a laptop without tags still builds.
- **The snapshot number moves without a pull request.** The commit after `v0.21.2` publishes as `0.21.3-snapshot…`, and the first product `feat` after it
  publishes as `0.22.0-snapshot…`. ADR-025 called the mid-cycle move an accepted oddity. It is now the normal case, and `helm list` on staging shows it.
- **A dry run is the only rehearsal of `cut-release.yml`.** The workflow cannot run from a pull request. The first cut after the implementation merges is a
  dry run, read before the real one.
- **`helm install` from a checkout installs `0.0.0`.** The chart's placeholders were right by convention before and are a plain `0.0.0` now. The published
  chart is the one a cluster uses, and it is stamped.
- **The four-file check is gone, and so is the class of defect it caught.** Nothing can drift because nothing is repeated.
- **Three agent memories and two operator pages are obsolete.** The notes describe traps that no longer exist. Staging pinned to the release until the bump
  merges, the bump PR found by author, and the raise stop read as a failed run. [DEVELOPMENT.md §Versions](../DEVELOPMENT.md#versions-and-cutting-a-release)
  and [RELEASING.md](../ops/RELEASING.md) describe the four files and the bump. The implementation rewrites both.
- **The unwelcome half.** `deserved` is now on the path of every build, not only of the cut. A commit subject that is not Conventional Commits was always
  a patch. It is now a patch that every snapshot of the cycle is named after. The raise pull request's reviewer was the check on that, and is gone.
  `label-pr.yml` is the remaining guard, and it guards titles, not commits.

## When to revisit

- **If a second committer arrives.** ADR-025 said a title check would then be worth its cost. With the raise pull request gone, that check is the only
  reading of the `feat` marker before it names a release.
- **At `1.0.0`.** `at_least: major` on the dispatch is the one decision the commits cannot show, and it is unchanged. The `!` marker moves the major from
  then on, and now moves every snapshot's number as well.

## References

- [#1614](https://github.com/enorm-labs/event-junkie/issues/1614) — the decision issue, with the measured cycle of `v0.21.1`
- [ADR-025](ADR-025_RELEASE_VERSION_FROM_COMMITS.md) — the rule for the number, and the constraint this ADR supersedes
- [ADR-016](ADR-016_GITOPS_DELIVERY.md) — how a version reaches a cluster, unchanged
- [#868][868] — `cut-release.yml`, and why the version is never typed
- [#455][455] — why snapshots must order
- [#1117][1117] — why a cut refuses a red snapshot
- [Semantic Versioning 2.0.0](https://semver.org/), §9 on prerelease ordering

[868]: https://github.com/enorm-labs/event-junkie/issues/868
[455]: https://github.com/enorm-labs/event-junkie/issues/455
[1117]: https://github.com/enorm-labs/event-junkie/issues/1117
