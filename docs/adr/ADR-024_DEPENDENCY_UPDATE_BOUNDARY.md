# ADR-024: Three mechanisms watch versions, and the boundary between them is the decision

## Status

**Accepted (2026-09-04) — Renovate is adopted, scoped by an allow-list to what belongs to no Dependabot ecosystem and is not a workflow string pin. Dependabot
keeps its six ecosystems.**

**Amended (2026-09-04, [#1071](https://github.com/enorm-labs/event-junkie/issues/1071)): Renovate also takes the CI tool versions pinned as plain strings in
`.github/workflows/`, and the nightly `agent-dependencies.yml` workload is retired.** The original boundary gave those eight pins to `/update-dependencies`
Step 12, on the grounds that it could push them since #996. Three of the four inputs to that reasoning did not survive testing — see _Amendment_ below.

**Amended (2026-09-04, [#1068](https://github.com/enorm-labs/event-junkie/issues/1068)): the two node pins in `infra/` are watched by a reminder, not by a
bot.** This file recorded them as deliberate gaps. `node-pin-reminder.yml` opens an issue when either falls behind, and opens no pull request. The reason a bot
may not own them is now part of the mechanism rather than a note here.

Decided in [#384](https://github.com/enorm-labs/event-junkie/issues/384), which was open for three weeks and rewritten once. **Supersedes nothing.** It records
a boundary that did not exist before, and retires the hand-written cluster-component list that `/update-dependencies` Step 13 used to carry.

## Context

A version pinned as a plain string belongs to no ecosystem, so nothing watches it. The failure is not a red check. It is a green check that quietly stopped
meaning anything. For a cluster component the consequence is worse. A stale `cert-manager` sits on the certificate path, and a stale Flux is the controller
that reconciles everything else.

**What was already covered when this was decided:**

|                                      |                                                                                                  |
| ------------------------------------ | ------------------------------------------------------------------------------------------------ |
| Dependabot                           | Six ecosystems — `gradle`, `npm`, `github-actions`, `opentofu`, `docker`, `docker-compose`       |
| `/update-dependencies` Step 12       | Tool versions pinned as strings in `.github/workflows/` — and it can **push** them since #996    |
| `/update-dependencies` Step 13       | A hand-written list of six cluster components, swept by `helm search` — human-triggered          |
| Flux, cert-manager, OpenObserve, …   | Watched by **nothing**. Chart versions inside a `HelmRelease` belong to no Dependabot ecosystem  |
| gitleaks' `rev:`, the Gradle wrapper | Watched by **nothing**. Dependabot has no `pre-commit` ecosystem and does not update the wrapper |

**What forced the decision** was #996 closing on 2026-09-03. The issue said outright that _"a stopgap that cannot push is not a stopgap"_. It also said the
"run the skill on a schedule" option could not be priced until the agent workload could open its own pull request. Once it could, half the case for
Renovate evaporated. The half that remained got sharper.

**Measured, not assumed.** A `renovate --platform=local --dry-run=full` against the tree found two things that three hand inventories of this repository all
missed. The first was `busybox:1.37.0`, an initContainer image nested inside a kustomize patch in a HelmRelease's values. The second was **three** tracked
`gradle-wrapper.properties` files, two of them vestigial and two minor versions behind.

It also found the live drift that makes the case. `gotk-components.yaml` pinned Flux at **v2.9.4** on both clusters. `FLUX_VERSION` in `validate-chart.yml`
already moved to **2.9.5**. The CLI that validated the manifests ran ahead of the controllers that reconciled them, and nothing reported it.

## Candidate options

- **Replace Dependabot with Renovate entirely.** One bot, one config, one mental model. It costs a rewrite of six working ecosystems as Renovate rules, with
  no gain on any of them. That includes a grouping scheme and a reasoned TypeScript suppression.
- **Run Renovate over everything, alongside Dependabot.** Two bots proposing the same bump. Worse than either alone, and the issue said so from the start.
- **Leave it all to `/update-dependencies` on a schedule.** Viable since #996. It costs an agent run every night, and finds nothing on roughly 29 nights in 30. It also depends on a hand-maintained list staying correct. Step 13's list named the wrong file for three of its six components.
- **`customManagers` regex over `.github/workflows/`.** Renovate can watch an arbitrary string given a `# renovate:` comment. Duplicates Step 12 exactly.
- **Renovate scoped by an allow-list to what nothing else owns.** One config file, no overlap by construction.

## Comparison

|                              | Duplicates Dependabot | Covers Flux + charts | Covers pre-commit, wrapper | Cost when nothing changed      |
| ---------------------------- | --------------------- | -------------------- | -------------------------- | ------------------------------ |
| Replace Dependabot           | —                     | yes                  | yes                        | rewrite six working ecosystems |
| Renovate over everything     | **yes**               | yes                  | yes                        | duplicate pull requests        |
| Skill on a schedule          | no                    | by hand              | no                         | **an agent run every night**   |
| `customManagers` for CI pins | duplicates Step 12    | no                   | no                         | —                              |
| **Renovate, allow-listed**   | **no**                | **yes**              | **yes**                    | **nothing**                    |

## Decision

**Renovate, with `enabledManagers` as an allow-list**: `flux`, `helm-values`, `kubernetes`, `pre-commit`, `gradle-wrapper`. Config in
`.github/renovate.json5` — JSON5 so it carries its reasoning, next to `dependabot.yml` so the boundary is readable from either side.

**The boundary, and why each edge is where it is:**

- **Dependabot owns its six ecosystems**, because a manifest it understands beats a pattern we maintain. A new dependency type goes here first.
- **Renovate owns the CI tool pins in `.github/workflows/` too.** `customManagers` resolves all eight without a comment beside any pin. The section below has
  the reasoning.
- **Renovate owns the rest**, because it brings purpose-built managers rather than regex over YAML. The `flux` manager reads `HelmRelease` versions and
  `gotk-components.yaml`. The `kubernetes` manager reads images in plain manifests.
- **A reminder owns the two node pins**, `k3s_version` and `walg_version`. Neither belongs to an ecosystem, and neither may be proposed by a bot. The section
  _Why the node pins get a reminder_ below has the reasoning.

**The decisive argument is cost shape.** A nightly agent run pays for every night it finds nothing. An event-driven bot does not: a cert-manager release opens a
pull request the day it ships, at zero marginal cost. For "watch twelve version strings", that is the right instrument and an LLM with sixty turns is the wrong
one.

**Flux is in scope**, which was not the initial recommendation. That is safe here for one reason. Every local customisation is already a kustomize patch in
`flux-system/kustomization.yaml` (#416, #604), never an edit to the generated file. This repository engineered that property deliberately, and wrote it down
twice, before anyone considered Renovate.

**This ADR first said Renovate regenerates the manifest. It does not.** The claim came from Renovate's own documentation. That documentation warns that custom
bootstrap flags "will be overwritten with the Flux defaults", which only replacement could produce. The first real pull request (#1075) showed otherwise. Renovate
substitutes strings: 72 version labels, 8 image tags, 2 header comments, and nothing else.

The safety argument above is unaffected, because the customisations are out of the file either way. **The risk is different, though, and it is worse in one
respect.** For v2.9.4 to v2.9.5 a string edit was provably complete, because the result was byte-identical to `flux install --export`. A release that also
changes RBAC, a controller flag or a resource limit is different. The manifest would then carry the new version label and the old structure, and it would render,
validate and reconcile without complaint. So every Flux pull request runs one check before any other:

```sh
flux install --export \
  --components=source-controller,kustomize-controller,helm-controller,notification-controller \
  | diff - deploy/clusters/staging/flux-system/gotk-components.yaml
```

Empty output means the bump is complete. Any output is what the string edit could not reach.

**Nothing automerges.** Every dependency here changes what runs in a cluster, what builds the artifacts, or what scans the commits.

## Consequences

- **Twelve dependencies that nothing watched are now watched.** They are Flux, cert-manager, the observability stack, the DNS-01 webhook, three cluster
  images, gitleaks and the Gradle wrapper. `cert-manager` is grouped across both clusters, and the observability charts as a set. A merge therefore cannot
  leave staging and production on different versions.
- **`/update-dependencies` Step 13 no longer sweeps.** It became a review checklist of what a person must check and Renovate cannot know. ADR-015's footprint
  measurement is pinned to OpenObserve 0.92.2. The `ZO_*` defaults move between releases. Digest-pinned images need the tag and the digest moved together. A
  Flux upgrade needs the Pod Security Admission re-check.
- **Every Renovate pull request needs a human.** With no automerge, the review is the control. For Flux that review is not a diff read. It is the export diff
  above, and then a check that the new controllers still satisfy `restricted`.
- **A second bot to reason about**, and one more place to look before concluding something is unwatched. The allow-list is what keeps that cost bounded: a
  manager absent from it cannot act, whatever it finds.
- **`milestone-dependabot.yml` matches bots by login**, so it had to learn `renovate[bot]`. Any future bot is invisible to it until added — and invisibly, since
  the skip is logged as normal operation.
- **The two node pins are watched, and still nothing proposes them.** `k3s_version` and `walg_version` in `infra/modules/environment/variables.tf` are read
  weekly by `scripts/upstream-node-pins.sh`, and `node-pin-reminder.yml` turns a gap into an assigned issue. Detection and proposal come apart here on purpose.
  See below.
- **One deliberate gap remains.** imgproxy stays unwatched because it pins a bare digest with no tag. Its version rests on a hand-made judgement about CRITICAL
  findings that an updater cannot reproduce.

## Why Renovate owns the CI tool pins

These eight are the pins with no manifest: `HELM_VERSION`, `SHELLCHECK_VERSION`, `TRIVY_VERSION`, `HELM_UNITTEST_VERSION`, `FLUX_VERSION`,
`FLUX_SCHEMA_VERSION`, `ZIZMOR_VERSION` and `ACTIONLINT_VERSION`, across fourteen occurrences in six workflows. Four facts put them with Renovate rather than
with a scheduled agent, and each was measured in [#1071](https://github.com/enorm-labs/event-junkie/issues/1071).

**`customManagers` reads them without touching the workflows.** `depNameTemplate` and `datasourceTemplate` carry the mapping in `renovate.json5`, so no
`# renovate:` comment sits beside any pin and nothing can drift out of step with one. A dry run resolves all eight.

**A pin that lives in three files becomes one pull request.** Renovate groups by `depName`. `HELM_VERSION` and `SHELLCHECK_VERSION` each appear in three
workflows and must move together or the gates disagree with each other. That grouping is automatic here and was a hand step before.

**Every pin is exercised by a check on the pull request that bumps it.** Three of the six workflows carry no `paths:` filter. The other three list their own
file. So a bump runs the new tool version before anyone merges it.

**A scheduled agent adds nothing to that.** Its detection is slower, costs a run per night, and duplicates what an event-driven bot does for free.

**What none of it covers is a scanner that quietly covers less.** CI proves a tool runs. It does not prove the tool still reads everything it did. A Trivy or
zizmor that audits a narrower set passes every check.

That property belongs in the gates rather than in whoever proposes a bump. The configuration route is the reason. A new suppression or a narrowed path moves
the finding set, and no updater ever sees it. [#1087](https://github.com/enorm-labs/event-junkie/issues/1087) put it there.
`scripts/scan-coverage.sh` now asserts a denominator on every scanner gate. This decision is therefore about who proposes a bump, never about who checks it.

**Splitting by pin is rejected**, scanners to an agent and tools to Renovate. It leaves two mechanisms with nothing enforcing which pin belongs to which, and
that is the shape this ADR exists to prevent.

## When to revisit

- **If the `pre-commit` manager is ever disabled**, gitleaks' `rev:` becomes watched by nothing and no error says so. This ADR moved
  `.pre-commit-config.yaml` to Renovate (#1067) and left no second mechanism holding it. Give it one in the same change, or the switch-off is silent.
- **If `FLUX_VERSION` and `gotk-components.yaml` drift again.** Renovate owns both since #1071, so a release produces two pull requests. They stay separate
  because the CLI and the controllers are separate things. Merge them together, and if they diverge again, fix the grouping.
- **If Renovate opens a pull request against anything in Dependabot's six.** The allow-list failed. That is a bug in this decision, not a merge conflict.
- **If the Mend hosted Community tier stops being free**, or its limits start to bind. One concurrent job and 4-hourly scheduling are generous at twelve
  dependencies, and would not be at a hundred. The fallback is self-hosting through Actions, which costs two new pinned versions that nothing watches.
- **If a scanner gate stops asserting a denominator.** [#1087](https://github.com/enorm-labs/event-junkie/issues/1087) made the finding-set comparison a
  gate. A bump that narrows coverage now fails. A new scanner needs the same treatment, and `scripts/scan-coverage.sh` says which of its three shapes fits.
- **If anything ever proposes a node pin as a pull request.** That reverses the decision below rather than extending it, and the wal-g failure mode is the
  thing to re-read first.
- **If `wal-g` stops publishing a `wal-g-pg-24.04` build**, or renames it. `backups.sh` builds that asset name by hand, and
  `scripts/upstream-node-pins.sh` fails rather than reporting no update.
- **If a third bot is proposed.** Read this file first. The question is never "is it useful" but "what does it own that nothing else does".

## Why the node pins get a reminder

`k3s_version` and `walg_version` are `default =` strings in `infra/modules/environment/variables.tf`. Dependabot's `opentofu` ecosystem reads providers and
modules, so it cannot see either. k3s is not apt-managed, so `unattended-upgrades` never meets it. Three facts put them with a reminder rather than with
Renovate, and each is why the detection is split from the proposal.

**A bump replaces the node.** Both values feed cloud-init, and `user_data` is force-new in the Hetzner provider. Changing either destroys and recreates the
server, which for k3s is a cluster rebuild. A routine-looking patch pull request hides that, and merging one on autopilot is a worse failure than the drift.

**For wal-g a pull request is worse than useless.** `walg_checksums` carries the SHA-256 of both release tarballs, and `backups.sh` verifies against it under
`set -euo pipefail`. An updater can move the version string and cannot compute those two values. The result is a pull request that passes every check, because
nothing in CI boots a node, and that aborts the next rebuild.

**"Behind" for k3s is a channel, not a tag.** k3s publishes every supported minor line in one release list, back to v1.16. The question worth asking is the
`update.k3s.io` stable channel, and separately whether the gap crosses a minor. `scripts/upstream-node-pins.sh` asks both. A Renovate datasource asks neither.

The reminder is the third of the shape `restore-drill-reminder.yml` and `credential-expiry-reminder.yml` already carry. It opens one assigned issue per pin.
Each title names the pinned version and never the target, so upstream moving cannot pile issues up. The wal-g issue carries both replacement checksums, which
makes the work an edit rather than a research task.

## References

- [#1068](https://github.com/enorm-labs/event-junkie/issues/1068) — the node pins, and the three options weighed before the reminder won
- [#384](https://github.com/enorm-labs/event-junkie/issues/384) — the issue, including the rejected options and three rounds of corrected evidence
- [#996](https://github.com/enorm-labs/event-junkie/issues/996) — `workflows: write` for the Claude App, which made Step 12 able to push and reshaped this decision
- [ADR-015](ADR-015_OBSERVABILITY_STACK.md) — the footprint measurement pinned to OpenObserve 0.92.2
- [ADR-016](ADR-016_GITOPS_DELIVERY.md) — GitOps delivery, and the accepted cost of pinning cert-manager in two files
- [ADR-017](ADR-017_JRE_BASE_IMAGE.md) — a vendor chosen largely on update-cadence grounds, and the precedent for this decision being ADR-shaped
- [`.github/renovate.json5`](../../.github/renovate.json5) · [`.github/dependabot.yml`](../../.github/dependabot.yml) · [`/update-dependencies`](../../.github/prompts/update-dependencies.prompt.md)
- [`.github/workflows/node-pin-reminder.yml`](../../.github/workflows/node-pin-reminder.yml) · [`scripts/upstream-node-pins.sh`](../../scripts/upstream-node-pins.sh)
