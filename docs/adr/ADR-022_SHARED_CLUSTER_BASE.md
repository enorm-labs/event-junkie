# ADR-022: The cluster directories share a base, and the canary moves into a patch

## Status

**Accepted (2026-09-02) — `deploy/clusters/base/` holds the seven files with no config differences, and each cluster patches one field. The files that
genuinely differ stay duplicated.**

Decided in [#953](https://github.com/enorm-labs/event-junkie/issues/953). **Partially supersedes
[ADR-016](ADR-016_GITOPS_DELIVERY.md)**, whose _When to revisit_ named this exact trigger. Everything else in ADR-016 stands, and the flat layout was correct
for the size the repository had then.

## Context

### The trigger fired, twice over

ADR-016 chose cluster directories with no shared base. It wrote its own reversal condition: _"A fourth environment, or the first time the three duplicated
policy blocks drift, justifies a `base/` overlay."_

Both halves happened.

- `deploy/clusters/k3d/` is a third directory, and its `namespace.yaml` is byte-identical to the other two.
- The copies drifted. `collector.yaml` carried **63 differing comment lines** for **one differing field**.

### The measurement

Every file that existed in both directories, counting config lines apart from comment lines:

| File                            | Lines | Config diff | Comment diff |
| ------------------------------- | ----- | ----------- | ------------ |
| `postgres-exporter.yaml`        | 101   | **0**       | 0            |
| `namespace.yaml`                | 23    | **0**       | 0            |
| `namespace-default.yaml`        | 30    | **0**       | 0            |
| `namespace-cert-manager.yaml`   | 29    | **0**       | 0            |
| `namespace-observability.yaml`  | 32    | **0**       | 6            |
| `helm-repository-jetstack.yaml` | 21    | **0**       | 14           |
| `collector.yaml`                | 654   | **1 field** | 63           |
| `notification.yaml`             | 66    | 4           | 28           |
| `cert-manager.yaml`             | 103   | 4           | 51           |
| `openobserve.yaml`              | 351   | 10          | 99           |
| `observability-netpol.yaml`     | 518   | 38          | 35           |
| `cert-manager-netpol.yaml`      | 221   | 50          | 105          |
| `helm-release.yaml`             | 193   | 43          | 172          |

Six files had no config difference at all. Four of the six were byte-identical. `collector.yaml` was 654 lines maintained twice for `k8sCluster`.

### The drift was in the reasoning, not the config

Four changes in one evening copied the same block into both files by hand. Each retyped the explanation instead of sharing it. The 63 comment lines are that
retyping. The duplication damaged the only part of these files that carries the argument.

`docs/ops/OPENOBSERVE.md` records the failure: _"the files are copies rather than one templated source, so a rule added to one misses the other"_.
Someone wrote that sentence after a rule missed the other.

### The argument against, which is the real decision

**Staging is a canary, and the duplication is what made it one.** #951 changed staging's collector alone. The result was measured. Only then did #952 copy the
change to production.

One shared file closes that gap by default. So the question was not whether a base is possible. It was which of the two properties is worth more.

## Candidate options

| Option                              | Cost                                                  | What it buys                                                |
| ----------------------------------- | ----------------------------------------------------- | ----------------------------------------------------------- |
| **Leave it**                        | The double edit, per change, forever                  | Greppable files, no indirection, the canary by accident     |
| **Share the zero-diff files only**  | One `../base` reference per cluster                   | 236 lines, no patches, no judgement calls                   |
| **The same, plus `collector.yaml`** | One 6-line patch per cluster                          | 654 more lines, and the file the drift actually happened in |
| **Full base and overlays**          | A 38-line patch for `observability-netpol.yaml` alone | Nothing the option above does not already give              |

Two ways exist to carry the cluster name. **Flux `postBuild.substitute`** puts the value on the Flux Kustomization in `gotk-sync.yaml`. That file is
machine-written and must not be hand-edited. #416 patched it once, and that patch merged without ever reaching the cluster. **A kustomize overlay patch** needs
no change to `flux-system/` at all.

## Decision

**Share the seven files. Patch one field. Keep the rest duplicated.**

`deploy/clusters/base/` holds the six zero-diff files and `collector.yaml`. Each cluster names `- ../base` in `resources:` and patches
`spec.values.k8sCluster` with JSON 6902 `replace`.

**The admission rule is one sentence: a file belongs in the base when its two copies have no config difference.** `collector.yaml` is the single exception, and
it differs in one field. `observability-netpol.yaml`, `cert-manager-netpol.yaml`, `helm-release.yaml`, `openobserve.yaml`, `cert-manager.yaml` and
`notification.yaml` stay duplicated. A patch of 38 or 43 lines reads worse than the second copy.

**The base value is `unset`, and both clusters patch it.** A cluster added without a patch then tags its telemetry `unset`. It does not claim to be an
environment it is not. `replace` fails the build if the key ever leaves the base, so a silent untagged collector is not reachable.

### Staging stays a canary, and the canary moves into the patch

**The canary is a property of the rollout, not of the directory layout.** It cost a permanent 613-line copy. It now costs a temporary patch.

1. The change lands in `base/collector.yaml`, with a production patch that pins the old value.
2. Staging runs it, and the result is measured, as #951 measured 10 rejection batches per restart to 0.
3. Deleting the production patch promotes the change. #952 becomes a deletion instead of a second copy.

The objection is fair and worth stating: a pinning patch is duplication with a different name. **Two things separate it from the copy it replaces.** The patch
exists only while a change is under test, and a reviewer sees it in the diff. The permanent copy exists always, and it is invisible on every day nobody uses it.

### Nothing the clusters run changed

`kubectl kustomize` rendered both directories before and after. Both renders are byte-identical, at 7,621 lines for staging and 7,358 for production. That
render diff is the safety argument for this change, and it runs on a laptop before anything reaches a cluster.

## Consequences

### Positive

- **A rule added to the collector reaches both clusters.** The failure `OPENOBSERVE.md` recorded is gone for that file.
- **A chart version bump for the collector or the operator is one edit.** `/update-dependencies` names one path instead of two.
- **The comments merged.** One argument now exists where two drifting copies did.

### Negative

- **"What does production run" is now a command, not a file.** `kubectl kustomize deploy/clusters/production` answers it. Reading one directory does not.
- **An edit to the base changes both clusters at once.** That is the point, and it is also the new way to break both at once.
- **`scripts/cluster-assertions.sh` had to learn the layout.** Its namespace check searched one directory. It now searches `base/` as well, but only when the
  cluster's `kustomization.yaml` names `- ../base`. The check reads the reference rather than assuming it, because a manifest that no `resources:` list names
  never reaches the cluster.
- **The base is not a cluster, and three globs still assume every child of `clusters/` is one.** `cluster-assertions.sh`, `deployed-versions.sh` and
  `validate-chart.yml` all glob a specific filename that the base does not hold, so each one skips it today. A file added to the base could change that.

## When to revisit

- **A file in the base grows a second per-cluster field.** One patched field is a patch. Three are an overlay, and the file may be worth duplicating again.
- **A third real cluster.** `k3d/` deliberately does not use the base, because the rehearsal does not run the collector or PostgreSQL metrics. A fourth
  environment that does run them makes that exclusion worth re-examining.
- **`openobserve.yaml` or `cert-manager.yaml` loses its config differences.** Each has fewer than ten. If a change removes them, the admission rule above
  already says what happens next.

## References

- [ADR-016](ADR-016_GITOPS_DELIVERY.md) — the flat layout, and the _When to revisit_ entry this answers
- [deploy/AGENTS.md](../../deploy/AGENTS.md) — the rules for touching any of this
- [docs/ops/OPENOBSERVE.md](../ops/OPENOBSERVE.md) — the filter rules, and which files are still copies
- [#953](https://github.com/enorm-labs/event-junkie/issues/953) · [#951](https://github.com/enorm-labs/event-junkie/issues/951) ·
  [#952](https://github.com/enorm-labs/event-junkie/issues/952) — the canary this decision had to preserve
