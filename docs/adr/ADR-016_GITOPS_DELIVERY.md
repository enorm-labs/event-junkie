# ADR-016: GitOps Delivery — Flux, pull-based

## Status

**Accepted (2026-08-12) — Flux, reconciling a Helm chart published to GHCR. CI builds and publishes; it never deploys.**

Decided on 2026-08-10 in [PLATFORM_SETUP §4](../PLATFORM_SETUP.md#4-how-deploys-happen) and implemented in
[#264](https://github.com/enorm-labs/event-junkie/issues/264) and [#414](https://github.com/enorm-labs/event-junkie/issues/414). This ADR records it properly,
because the reasoning is load-bearing, it was arrived at by *reversing* an earlier decision, and one of its consequences is easy to state backwards.

**Partially supersedes [ADR-012](ADR-012_CLOUD_PLATFORM.md).** That ADR chose Hetzner and accepted a known weakness: *"GitHub Actions cannot use OIDC against
Hetzner, so deploys authenticate with a scoped kubeconfig or deploy key held as a repository secret, rotated deliberately. This is a genuine step down from
AWS/GCP OIDC and should be treated as such."* **That weakness no longer exists** — not because it was mitigated, but because the credential it describes was
removed. ADR-012's platform choice stands unchanged; only that paragraph is obsolete.

## Context

### The constraint that decided it

Push-based deploys were chosen first and then withdrawn, and the reason is arithmetic rather than philosophical:

> `helm upgrade` from GitHub Actions requires the runner to reach the Kubernetes API on port 6443. `admin_cidr` exists to make exactly that unreachable. The
> obvious fix — allowlist GitHub's runners — **is arithmetically impossible**: GitHub publishes **7,297 CIDRs** for Actions (5,658 IPv4), and a Hetzner cloud
> firewall permits **500 effective rules**. Off by a factor of fourteen, and the list changes.

The remaining ways to make push work were: leave 6443 open to the internet; tunnel through a US SaaS, against the posture #412 established; hand-roll WireGuard
inside a runner; or put a self-hosted runner on the node — ruled out outright, because GitHub warns against self-hosted runners on **public** repositories, where
a fork's pull request can execute arbitrary code on what would here be the production node.

Pull-based delivery dissolves the problem rather than working around it: the cluster reaches *out*, so nothing inbound is required and 6443 need never be publicly
reachable at all.

### Scale

**One application, one chart, one developer, one or two clusters.** That number matters more than it looks — most GitOps tooling is built for many apps, many
clusters and many teams, and the cost of that generality is paid in components that have nothing to do here.

## Options

| | Cost | What it buys |
|---|---|---|
| **Plain Helm from CI** | 0 MB | Simplest, and the cluster holds no deploy machinery. **Impossible here** — see above |
| **Flux** | ~300 MB | Real GitOps: the cluster reconciles itself, drift is corrected, rollback is a git revert. Four controllers, no UI |
| **ArgoCD** | ~1.2 GB | The same, plus the UI that is the actual reason people choose it. Would force a larger node |

## Decision

**Flux.** Four controllers (source, kustomize, helm, notification), ~300 MB, comfortable on the CX33.

**Not ArgoCD**, because the only thing it adds here is a UI, and it costs 900 MB more and a node upgrade to have one.

**The chart is delivered as an OCI artifact, not from Git.** `helm push` puts a versioned, immutable tarball in GHCR next to the images that the same build
produced, and an `OCIRepository` watches it. The alternative — Flux rendering the chart directory out of this repository — would couple every deploy to the
repository layout and lose the property that a released chart cannot be edited after the fact.

**Each environment's version policy lives on its `OCIRepository`, and the two are deliberately opposite:**

| | Range | Effect |
|---|---|---|
| **staging** | `semver: ">=0.0.0-0"` | Follows every snapshot published from `main` |
| **production** | `semver: ">=0.1.0"` + `semverFilter: '^[0-9]+\.[0-9]+\.[0-9]+$'` | Only a published GitHub Release |

**The `-0` is the entire mechanism**, and its absence fails silently: Helm's constraint syntax skips prerelease versions unless the range admits them, so a
staging range without it matches no snapshot at all and the environment simply stops moving. Production carries a regex filter *as well as* a range because
excluding snapshots by omission is one careless `-0` away from being wrong.

**Repository structure: cluster directories only, no `apps/` + `infrastructure/` split.** Flux's [repository structure
guide](https://fluxcd.io/flux/guides/repository-structure/) recommends a monorepo layout of `apps/{base,staging,production}`,
`infrastructure/{base,staging,production}` and `clusters/*` wired together with `Kustomization` objects and `dependsOn`. **That structure exists to order
infrastructure before applications and to share a base across many apps, and we have one application and no Flux-managed infrastructure** — cert-manager and
Traefik are installed out of band (#265), and Traefik ships with k3s. An `infrastructure/` tree would be empty and a `base/` would have exactly one member. So
`deploy/clusters/<env>/` holds the resources directly. See *Consequences* for what this costs.

## Consequences

**Good:**

- **CI holds no cluster credential, because there is none to hold.** A repository compromise no longer implies a *credentialled* path into the cluster.
- **Rollback is `git revert`**, and drift is corrected rather than merely detected.
- **Verification runs where the workloads do.** CI cannot reach staging by design, so the chart's `helm test` hook is the smoke test, run by Flux as part of
  reconciliation, with remediation on failure.

**The consequence that is easy to state backwards, and usually is:**

**Flux does not remove the power to change the cluster — it relocates it into the repository.** `kustomize-controller` and `helm-controller` are bound to
`cluster-admin`, so whoever can push to `deploy/clusters/**` on `main` can have the cluster apply anything, with nothing to steal because the cluster fetches it
willingly. What Flux removes is the *stored credential*. **Branch protection is the control that replaces the kubeconfig**, which is why
[#443](https://github.com/enorm-labs/event-junkie/issues/443)'s missing required status checks matter more after this decision than before it.

**Costs accepted:**

- **Deploys become eventually-consistent.** A green Actions run means "the artifacts exist", not "it is live". Reconciliation lands within about one polling
  interval — 1m on staging, 10m on production.
- **Webhooks are ruled out permanently.** A `Receiver` is an inbound HTTP endpoint, and §8's firewall design exists to have nothing inbound. So the interval is
  the deploy latency, and Flux's own guidance not to poll below 30s without webhooks sets the floor.
- **The cluster gains one GitHub credential after all** — a fine-grained PAT with *commit statuses: write* on this repository only, so the notification
  controller can report reconciliation back onto the commit. Narrow, single-purpose, and a deliberate exception to "the cluster holds nothing" rather than an
  erosion of it.
- **Three cluster directories duplicate their remediation policy.** Without a `base/`, a change to the release policy has to be made three times — which has
  already cost something: the `remediateLastFailure` fix below was applied three times by a script. Worth revisiting if a fourth environment appears, or if the
  duplicated blocks drift.

**One default that had to be found by running it, not reading it:**

`HelmRelease.spec.upgrade.remediation.remediateLastFailure` **defaults to `false`**, which means remediation runs *between* retry attempts and never after the
final one — exhaust the retries and the cluster keeps serving the release that failed. A deliberately broken release on k3d was correctly rejected and then left
running. Every `HelmRelease` here sets it to `true` on `upgrade`, and deliberately **not** on `install`, where remediation is an uninstall and there is no
previous version to return to.

### When to revisit

- **A second application**, or infrastructure that Flux manages, makes the guide's `apps/` + `infrastructure/` split earn its keep.
- **A fourth environment**, or the first time the three duplicated policy blocks drift, justifies a `base/` overlay.
- **ArgoCD** becomes worth reconsidering only if a UI becomes a requirement *and* the node grows for another reason.

## References

- [PLATFORM_SETUP §4, §4a](../PLATFORM_SETUP.md#4-how-deploys-happen) — the setup-level detail and the deployment-visibility design
- [RELEASING.md](../RELEASING.md) — the end-to-end path a commit takes to become a running deployment
- [ADR-012](ADR-012_CLOUD_PLATFORM.md) — the platform, and the credential weakness this supersedes
- [deploy/AGENTS.md](../../deploy/AGENTS.md) — the traps, and the rules for touching any of this
- Flux: [repository structure](https://fluxcd.io/flux/guides/repository-structure/) · [security](https://fluxcd.io/flux/security/) ·
  [end-to-end](https://fluxcd.io/flux/flux-e2e/)
