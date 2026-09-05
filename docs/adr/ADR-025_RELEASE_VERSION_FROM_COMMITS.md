# ADR-025: The release number is read from the commits, not chosen

## Status

**Accepted (2026-09-05) — a release's number is read from the Conventional Commits since the last release tag, by SemVer. `cut-release.yml` refuses
a tree that says less. A `feat` earns a minor, a breaking change a major (a minor before `1.0.0`), everything else a patch.**

**Implemented in [#1163](https://github.com/enorm-labs/event-junkie/pull/1163) on 2026-09-05.** `scripts/version.sh deserved` applies the rule,
`scripts/version-deserved-test.sh` asserts it, and the first dry run on `main` reported "not cut, raise to 0.4.0" as designed.

**Does not supersede anything.** [ADR-016](ADR-016_GITOPS_DELIVERY.md) decided how a version reaches a cluster and constrained the snapshot scheme to
order. It did not decide what number a release gets. [DEVELOPMENT.md §Versions](../DEVELOPMENT.md#versions-and-cutting-a-release) records the scheme
and the four files. This ADR decides the one thing both left to a person: how far the number moves.

## Context

Every release from `v0.3.0` to `v0.3.12` was a patch. `v0.3.9` carried fifteen `feat` commits and `v0.3.8` five. The numbers said nothing changed, and a
reader of the Releases page could not tell a fix-only release from one that added six venues.

**What forced the decision** was the shape of the old flow, not a lack of care. `cut-release.yml` took a `bump` input and applied it to the snapshot that
`main` moves on to after the release. So the number of a release was chosen at the cut before it, by whoever ran it. Nobody knew yet what the next
release would hold. The input's own description said so and defaulted to `patch` for that reason. A person deciding under those terms always picks the
smallest number, and did.

The evidence to decide from already existed. Every pull request title here is a Conventional Commits subject, `label-pr.yml` derives labels from it, and
`.github/release.yml` builds the release notes from those labels. The type that names a feature and the marker that names a breaking change were on every
commit, and nothing read them for the number.

**The constraints any candidate had to satisfy:**

| Constraint                                                                 | Fixed by                                                                              |
| -------------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| The tag must equal `gradle.properties`, so a release number is never typed | `scripts/version.sh compute` refuses a tag that disagrees with the tree ([#868][868]) |
| A release is a rebuild of a green snapshot of the same commit              | `cut-release.yml` refuses a commit whose snapshot publish is red ([#1117][1117])      |
| Snapshots must order, and a snapshot is a prerelease of the coming release | ADR-016 and the timestamped scheme ([#455][455])                                      |
| Nothing in CI pushes to `main`                                             | the `main` ruleset, so every version change is a pull request                         |
| The rule has to be checkable by a script and by a person, with one answer  | AGENTS.md, which puts version logic in `scripts/version.sh` and nowhere else          |

The first constraint is the one that shapes the answer. The number cannot be decided at the tag, because the tag must match a tree that was already built
and published. It has to be in the tree before the cut, which means a pull request, which means the cut cannot do it in one run.

## Candidate options

1. **Keep the `bump` input and write down when to use `minor`.** A rule in a document that a person applies at the wrong moment. The moment is still
   before the content is known, so the document changes nothing about the incentive.
2. **Derive the number at the cut, and refuse a tree that says less.** The workflow reads the commits since the last tag and compares with the tree.
   When they differ it opens the raise pull request. The cut then takes two dispatches in a cycle that added a feature. This is the option taken.
3. **Raise the tree the moment a `feat` merges.** A workflow on every push to `main` that opens the raise pull request as soon as the commits deserve
   more. It removes the second dispatch. It adds a workflow and an App token mint on every push. Its pull request can race the bump pull request the
   cut opens. Not taken now, and it is the natural next step if the two-dispatch cut proves tiresome.
4. **Adopt a release tool such as release-please or semantic-release.** Both derive the number the same way. Both also own the changelog, the tag and the
   release. `cut-release.yml` and `.github/release.yml` already own those, with reasons written down. The one missing feature is ninety lines in
   `scripts/version.sh deserved`. That did not justify a second system.

## Decision

Option 2. The number is read from the commits since the last release tag and the cut enforces it.

- A breaking change, marked `!` in the subject or `BREAKING CHANGE:` in the body, is a **major**. Before `1.0.0` it is a **minor**, because SemVer §4 says a
  `0.y.z` release may change anything, and the minor is the number that signals it.
- A `feat` in any scope is a **minor**. A new event source is a `feat`.
- Everything else is a **patch**. A subject that is not Conventional Commits is a patch and is listed, so an unlabelled feature is visible.
- A revert is a patch. The commits it reverts still count, and undoing that is a judgement the rule does not make.
- `at_least` is a floor on the dispatch, for the one decision the commits cannot show. `1.0.0` is cut with `major`. The floor never lowers the verdict.

What "breaking" means here is a consumer having to act. That is the BFF's public `/api/**` contract, the chart's `values.yaml` keys, the importer's admin
API, or a step an operator must take before the upgrade. A change to a scraper is not breaking, however large.

After a release, `main` moves to the next **patch** snapshot, because nothing is known about the next release yet. The tree is raised later, when the
commits earn it, by the pull request the cut opens. A run that opens that pull request ends red, so a release that was asked for and not made never
reads as green.

What settled it was the first constraint above. The number has to be in the tree, and the tree is built and published before the cut. So the decision
belongs at the cut, with the evidence, and the cost is a second dispatch in the cycles that add a feature.

## Consequences

- **Most releases are now minors.** Fifty-four venue importers exist and each new one is a `feat`. That is the honest number, and the Releases page will
  move through `0.4`, `0.5`, `0.6` faster than it moved through `0.3.x`. Nothing depends on the minor staying small.
- **A cut can take two dispatches**, and the second waits for a snapshot publish of the raise commit. On a day with a red base image that is two blocked
  runs rather than one.
- **The `!` marker is now load-bearing.** Before, it sorted a pull request into a release-notes section. Now it moves the major once the project is past
  `1.0.0`. A `!` on a change that breaks nothing costs a number, and a missing one hides a break. Review has to read the title for it.
- **A commit that is not Conventional Commits is cheap by default.** The rule cannot read a feature out of a free-text subject. The summary lists such
  commits, and the reviewer of the raise pull request is the check.
- **`scripts/version.sh` now reads history.** It needs the tags, so `cut-release.yml` checks out with `fetch-depth: 0`. A shallow clone anywhere else that
  calls `deserved` dies with "no release tag", which is the right failure.
- **The snapshot number a cycle publishes under can change mid-cycle**, from `0.4.1-snapshot.…` to `0.5.0-snapshot.…`. Staging follows, because the raised
  number sorts above. A person reading `helm list` on staging sees the number move without a release, and that is expected.
- **The unwelcome half.** The rule is mechanical and the marker is human. It fixes the number to what the commits say, not to what the release is. A
  release whose one `feat` is trivial is still a minor, and there is no input to say otherwise. That is deliberate: the input that could say otherwise is
  the one that produced twelve patches.

## When to revisit

- **When the two-dispatch cut annoys for the third time.** Option 3 is the answer, and the raise pull request the cut opens is the shape it would
  automate.
- **At `1.0.0`.** The pre-`1.0.0` clause stops applying by itself. What changes is the weight of the `!` marker, and the review habit has to be in place
  before the first major is a surprise.
- **If a second committer arrives.** The rule assumes one person reads every title. A check on the pull request title would then be worth its cost. Today the
  labeller's silence is the only enforcement.

## References

- [#1163](https://github.com/enorm-labs/event-junkie/pull/1163) — the implementation
- [#868][868] — `cut-release.yml`, and why the version is never typed
- [#455][455] — why snapshots must order, the constraint ADR-016 placed on the scheme
- [#1117][1117] — the empty tag, and why a cut refuses a red snapshot
- [RELEASING.md § What a release deserves](../ops/RELEASING.md#what-a-release-deserves) — the rule as an operator reads it
- [Semantic Versioning 2.0.0](https://semver.org/), §4 on `0.y.z` and §8 on what a major is
- [Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/), on `!` and the `BREAKING CHANGE:` footer

[868]: https://github.com/enorm-labs/event-junkie/issues/868
[455]: https://github.com/enorm-labs/event-junkie/issues/455
[1117]: https://github.com/enorm-labs/event-junkie/issues/1117
