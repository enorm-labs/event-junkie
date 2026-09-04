# ADR-024: Three mechanisms watch versions, and the boundary between them is the decision

## Status

**Accepted (2026-09-04) — Renovate is adopted, scoped by an allow-list to what belongs to no Dependabot ecosystem and is not a workflow string pin. Dependabot
keeps its six ecosystems. `/update-dependencies` Step 12 keeps the tool versions pinned in `.github/workflows/`.**

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
- **Step 12 owns the workflow string pins**, because it can push them since #996. This clause is a fact about _credentials_, not about capability — Renovate
  could watch them via `customManagers`. It does not, because two mechanisms on one file collide.
- **Renovate owns the rest**, because it brings purpose-built managers rather than regex over YAML. The `flux` manager reads `HelmRelease` versions and
  `gotk-components.yaml`. The `kubernetes` manager reads images in plain manifests.

**The decisive argument is cost shape.** A nightly agent run pays for every night it finds nothing. An event-driven bot does not: a cert-manager release opens a
pull request the day it ships, at zero marginal cost. For "watch twelve version strings", that is the right instrument and an LLM with sixty turns is the wrong
one.

**Flux is in scope**, which was not the initial recommendation. Renovate regenerates `gotk-components.yaml` wholesale, and its documentation warns that custom
bootstrap flags are lost. That is safe here for one reason. Every local customisation is already a kustomize patch in `flux-system/kustomization.yaml` (#416,
#604), never an edit to the generated file. This repository engineered that property deliberately, and wrote it down twice, before anyone considered Renovate.

**Nothing automerges.** Every dependency here changes what runs in a cluster, what builds the artifacts, or what scans the commits.

## Consequences

- **Twelve dependencies that nothing watched are now watched.** They are Flux, cert-manager, the observability stack, the DNS-01 webhook, three cluster
  images, gitleaks and the Gradle wrapper. `cert-manager` is grouped across both clusters, and the observability charts as a set. A merge therefore cannot
  leave staging and production on different versions.
- **`/update-dependencies` Step 13 no longer sweeps.** It became a review checklist of what a person must check and Renovate cannot know. ADR-015's footprint
  measurement is pinned to OpenObserve 0.92.2. The `ZO_*` defaults move between releases. Digest-pinned images need the tag and the digest moved together. A
  Flux upgrade needs the Pod Security Admission re-check.
- **Every Renovate pull request needs a human.** With no automerge, the review is the control. For Flux that review is not a diff read. It is a check that
  the regenerated controllers still satisfy `restricted`.
- **A second bot to reason about**, and one more place to look before concluding something is unwatched. The allow-list is what keeps that cost bounded: a
  manager absent from it cannot act, whatever it finds.
- **`milestone-dependabot.yml` matches bots by login**, so it had to learn `renovate[bot]`. Any future bot is invisible to it until added — and invisibly, since
  the skip is logged as normal operation.
- **Two deliberate gaps remain, and both are decisions rather than oversights.** `k3s_version` and `walg_version` stay unwatched in
  `infra/modules/environment/variables.tf`, because `user_data` is force-new. A bump _replaces the node_. A routine patch pull request would hide that.
  **#1068 carries that gap.** Whatever lands there amends this ADR. imgproxy stays unwatched because it pins a bare digest with no tag. Its version rests on a hand-made judgement about CRITICAL findings that an
  updater cannot reproduce.

## When to revisit

- **When the two vestigial Gradle wrappers are deleted (#1066)**, drop the `packageRules` entry that disables them.
- **If `FLUX_VERSION` and `gotk-components.yaml` drift again.** Renovate owns one and Step 12 owns the other. A second occurrence means the split is wrong,
  and one mechanism should own both.
- **If Renovate opens a pull request against anything in Dependabot's six.** The allow-list failed. That is a bug in this decision, not a merge conflict.
- **If the Mend hosted Community tier stops being free**, or its limits start to bind. One concurrent job and 4-hourly scheduling are generous at twelve
  dependencies, and would not be at a hundred. The fallback is self-hosting through Actions, which costs two new pinned versions that nothing watches.
- **If a third bot is proposed.** Read this file first. The question is never "is it useful" but "what does it own that nothing else does".

## References

- [#384](https://github.com/enorm-labs/event-junkie/issues/384) — the issue, including the rejected options and three rounds of corrected evidence
- [#996](https://github.com/enorm-labs/event-junkie/issues/996) — `workflows: write` for the Claude App, which made Step 12 able to push and reshaped this decision
- [ADR-015](ADR-015_OBSERVABILITY_STACK.md) — the footprint measurement pinned to OpenObserve 0.92.2
- [ADR-016](ADR-016_GITOPS_DELIVERY.md) — GitOps delivery, and the accepted cost of pinning cert-manager in two files
- [ADR-017](ADR-017_JRE_BASE_IMAGE.md) — a vendor chosen largely on update-cadence grounds, and the precedent for this decision being ADR-shaped
- [`.github/renovate.json5`](../../.github/renovate.json5) · [`.github/dependabot.yml`](../../.github/dependabot.yml) · [`/update-dependencies`](../../.github/prompts/update-dependencies.prompt.md)
