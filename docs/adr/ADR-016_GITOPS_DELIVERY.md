# ADR-016: GitOps Delivery — Flux, pull-based

## Status

**Accepted (2026-08-12) — Flux, reconciling a Helm chart published to GHCR. CI builds and publishes, and never
deploys.**

Decided on 2026-08-10 in [PLATFORM_SETUP §4](../ops/PLATFORM_SETUP.md#4-how-deploys-happen) and implemented in
[#264](https://github.com/enorm-labs/event-junkie/issues/264) and
[#414](https://github.com/enorm-labs/event-junkie/issues/414). This ADR records it properly for three reasons. The
reasoning is load-bearing, it _reverses_ an earlier decision, and one of its consequences is easy to state backwards.

**Partially supersedes [ADR-012](ADR-012_CLOUD_PLATFORM.md).** That ADR chose Hetzner and accepted a known weakness.
_"GitHub Actions cannot use OIDC against Hetzner. A deploy authenticates with a scoped kubeconfig or deploy key, held
as a repository secret and rotated deliberately. This is a genuine step down from AWS/GCP OIDC, and deserves to be
treated as one."_ **That weakness no longer exists.** It was not mitigated — the credential it describes was removed. ADR-012's
platform choice stands unchanged, and only that paragraph is obsolete.

## Context

### The constraint that decided it

Push-based deploys were chosen first and then withdrawn. The reason is arithmetic rather than philosophical:

> `helm upgrade` from GitHub Actions requires the runner to reach the Kubernetes API on port 6443. `admin_cidr` exists to make exactly that unreachable. The
> obvious fix is to allowlist GitHub's runners, and that **is arithmetically impossible**. GitHub publishes
> **7,297 CIDRs** for Actions (5,658 IPv4), and a Hetzner cloud
> firewall permits **500 effective rules**. Off by a factor of fourteen, and the list changes.

Four ways remained to make push work:

- leave 6443 open to the internet
- tunnel through a US SaaS, against the posture #412 established
- hand-roll WireGuard inside a runner
- put a self-hosted runner on the node

The last is ruled out outright. GitHub warns against a self-hosted runner on a **public** repository, where a fork's
pull request can execute arbitrary code — here, on the production node.

Pull-based delivery dissolves the problem rather than working around it. The cluster reaches _out_, so nothing inbound
is required and 6443 need never be publicly reachable at all.

### Scale

**One application, one chart, one developer, one or two clusters.** That number matters more than it looks. Most
GitOps tooling is built for many apps, many clusters and many teams. The cost of that generality is paid in components
that have nothing to do here.

## Candidate options

|                        | Cost    | What it buys                                                                                                      |
| ---------------------- | ------- | ----------------------------------------------------------------------------------------------------------------- |
| **Plain Helm from CI** | 0 MB    | Simplest, and the cluster holds no deploy machinery. **Impossible here** — see above                              |
| **Flux**               | ~300 MB | Real GitOps: the cluster reconciles itself, drift is corrected, rollback is a git revert. Four controllers, no UI |
| **ArgoCD**             | ~1.2 GB | The same, plus the UI that is the actual reason people choose it. Would force a larger node                       |

## Decision

**Flux.** Four controllers (source, kustomize, helm, notification), ~300 MB, comfortable on the CX33.

**Not ArgoCD**, because the only thing it adds here is a UI, and it costs 900 MB more and a node upgrade to have one.

**The chart is delivered as an OCI artifact, not from Git.** `helm push` puts a versioned, immutable tarball in GHCR
next to the images that the same build produced, and an `OCIRepository` watches it. The alternative is Flux rendering
the chart directory out of this repository. That couples every deploy to the repository layout, and loses the property
that a released chart cannot be edited after the fact.

**Each environment's version policy lives on its `OCIRepository`, and the two are deliberately opposite:**

|                | Range                                                            | Effect                                       |
| -------------- | ---------------------------------------------------------------- | -------------------------------------------- |
| **staging**    | `semver: ">=0.0.0-0"`                                            | Follows every snapshot published from `main` |
| **production** | `semver: ">=0.1.0"` + `semverFilter: '^[0-9]+\.[0-9]+\.[0-9]+$'` | Only a published GitHub Release              |

**The `-0` is the entire mechanism**, and its absence fails silently. Helm's constraint syntax skips a prerelease
version unless the range admits it. A staging range without the `-0` matches no snapshot at all, and the environment
simply stops moving. Production carries a regex filter _as well as_ a range, because excluding snapshots by omission is
one careless `-0` away from being wrong.

> **The `-0` decides which versions are _candidates_. It does not decide which candidate _wins_**
> ([#455](https://github.com/enorm-labs/event-junkie/issues/455)).
>
> The version scheme decides that. A range ranking unordered versions resolves a chart at random. That is the same
> silent shape as a missing `-0`, and arguably worse. The `OCIRepository` reports `Ready` with a plausible version,
> rather than not matching at all.
>
> **So the snapshot identifier must be monotonic, and that is a constraint this ADR places on the version scheme.**
> SemVer §11 gives three rules. An identifier made only of digits compares numerically. An identifier containing a
> letter compares lexically in ASCII. A numeric identifier ranks _below_ an alphanumeric one. `0.1.0-snapshot.g<sha>`
> fell on the wrong side of the first rule, because a short sha is effectively random. Staging resolved whichever sha
> sorted highest, ran a three-day-old chart, and surfaced it as a certificate that would not issue. The fix for #452
> was published and never deployed. Snapshots are now `0.1.1-snapshot.<utc-timestamp>.g<sha>`. The base version moved
> because of the third rule: a timestamped snapshot of `0.1.0` sorts under all ten legacy tags.
>
> The ordering is asserted rather than assumed. `scripts/version-test.sh` resolves fabricated version sets through the
> same Masterminds solver the source-controller embeds, and demands that the newest win. A format assertion is
> precisely the test that would not have caught this.

**Repository structure: cluster directories only, no `apps/` + `infrastructure/` split.** Flux's
[repository structure guide](https://fluxcd.io/flux/guides/repository-structure/) recommends a monorepo layout of
`apps/{base,staging,production}`, `infrastructure/{base,staging,production}` and `clusters/*`, wired together with
`Kustomization` objects and `dependsOn`. That structure buys two things: ordering, and sharing. **Neither is worth its
indirection at this size.** So `deploy/clusters/<env>/` holds the resources directly. See _Consequences_ for what this
costs.

> **Ordering is bought for free.** `dependsOn` on a `HelmRelease` orders installs without any directory structure. It
> is _required_ rather than decorative: the application chart renders a `cert-manager.io/v1` ClusterIssuer, and the
> API server rejects an unknown kind.
>
> **Sharing is what two clusters do not buy.** A `base/` would have two members, and cost an indirection to save one
> file. The distance to that line is short. `helm-repository-jetstack.yaml` and `cert-manager.yaml` are duplicated
> between staging and production, byte-for-byte apart from their comments, and a version bump has to be made twice.
> The _"when to revisit"_ entry below expects this. The third copy, or the first drift between the two, is the trigger.
>
> **There is Flux-managed infrastructure, and there was not meant to be** (#265). "Out of band" meant "typed into a
> terminal once and never versioned again". That is for a component whose silent failure is that certificates stop
> renewing and nobody finds out for sixty days. It is precisely the argument `infra/` makes about servers, and
> it does not stop being true one layer up. So cert-manager is a `HelmRelease` on both clusters, and the Hetzner DNS-01
> webhook is another on staging. Traefik is genuinely still out of band, because k3s installs it.

## Consequences

**Good:**

- **CI holds no cluster credential, because there is none to hold.** A repository compromise no longer implies a _credentialled_ path into the cluster.
- **Rollback is `git revert`**, and drift is corrected rather than merely detected.
- **Verification runs where the workloads do.** CI cannot reach staging by design, so the chart's `helm test` hook is
  the smoke test. Flux runs it as part of reconciliation, and remediates on failure.

**The consequence that is easy to state backwards, and usually is:**

**Flux does not remove the power to change the cluster. It relocates it into the repository.** `kustomize-controller`
and `helm-controller` are bound to `cluster-admin`. Whoever can push to `deploy/clusters/**` on `main` can have the
cluster apply anything, and there is nothing to steal, because the cluster fetches it willingly. What Flux removes is
the _stored credential_. **Branch protection is the control that replaces the kubeconfig**, which is why
[#443](https://github.com/enorm-labs/event-junkie/issues/443) matters more after this decision than before it.

> **That control had to be built before the sentence above was true.** The `main` ruleset required no status checks at
> all, _and_ carried `bypass_actors: [{OrganizationAdmin, always}]`. Every rule in it, including the pull-request
> requirement, was advisory for the only account that merges anything here. #443 fixed both. Nine checks are required
> and the admin bypass is removed. The combination was verified by watching a pull request with a failing check be
> refused (`the base branch policy prohibits the merge`), rather than by reading the configuration back.

**Costs accepted:**

- **Deploys become eventually-consistent.** A green Actions run means "the artifacts exist", not "it is live". Reconciliation lands within about one polling
  interval — 1m on staging, 10m on production.
- **Webhooks are ruled out permanently.** A `Receiver` is an inbound HTTP endpoint, and §8's firewall design exists to have nothing inbound. So the interval is
  the deploy latency, and Flux's own guidance not to poll below 30s without webhooks sets the floor.
- **The cluster gains one GitHub credential after all.** The notification controller reports reconciliation back to
  GitHub as a **deployment** ([#565](https://github.com/enorm-labs/event-junkie/issues/565)), which needs a
  fine-grained PAT with `contents: write` on this repository only. Single-purpose, and a deliberate exception to "the
  cluster holds nothing" rather than an erosion of it. It is also a stronger credential than the design it replaced.
  Reporting onto the commit cannot work, because a HelmRelease reports a chart version, not a commit
  ([#567](https://github.com/enorm-labs/event-junkie/issues/567)). See PLATFORM_SETUP §4a.

- **Staging gains a second credential, and it is a broad one** (#265). DNS-01 needs an hcloud API token in the
  cluster. An hcloud token is project-scoped, with no way to narrow it to "write TXT records under one zone". The same
  token could delete the servers. It is confined to staging, the environment that can be rebuilt from `infra/` in an
  afternoon. Production avoids it entirely, and solves HTTP-01 against an address the internet can reach. Worth
  restating as a rule: **production must not acquire this token to gain a wildcard certificate.** That trade is not
  worth it.
- **Three cluster directories duplicate their remediation policy.** Without a `base/`, a change to the release policy
  has to be made three times. That already cost something: a script applied the `remediateLastFailure` fix below three
  times. Worth revisiting if a fourth environment appears, or if the duplicated blocks drift.

**One default that had to be found by running it, not reading it:**

`HelmRelease.spec.upgrade.remediation.remediateLastFailure` **defaults to `false`**. Remediation then runs _between_
retry attempts and never after the final one. Exhaust the retries and the cluster keeps serving the release that
failed. A deliberately broken release on k3d was correctly rejected and then left running. Every `HelmRelease` here
sets it to `true` on `upgrade`. It is deliberately **not** set on `install`, where remediation is an uninstall and
there is no previous version to return to.

### When to revisit

- **A second application** makes the guide's `apps/` + `infrastructure/` split earn its keep. Flux-managed
  infrastructure was the other trigger. #265 brought it, and §Decision says why the flat layout survived that.
- **A fourth environment**, or the first time the three duplicated policy blocks drift, justifies a `base/` overlay.
  The duplication is wider than the policy blocks: cert-manager's `HelmRepository` and `HelmRelease` exist twice. A
  version bump is therefore two commits, or it is a divergence.
- **ArgoCD** becomes worth reconsidering only if a UI becomes a requirement _and_ the node grows for another reason.

## References

- [PLATFORM_SETUP §4, §4a](../ops/PLATFORM_SETUP.md#4-how-deploys-happen) — the setup-level detail and the deployment-visibility design
- [RELEASING.md](../ops/RELEASING.md) — the end-to-end path a commit takes to become a running deployment
- [CLUSTER_BOOTSTRAP.md](../ops/CLUSTER_BOOTSTRAP.md) — the once-per-cluster bring-up this decision implies, and what it cost to run the first time
- [ADR-012](ADR-012_CLOUD_PLATFORM.md) — the platform, and the credential weakness this supersedes
- [deploy/AGENTS.md](../../deploy/AGENTS.md) — the traps, and the rules for touching any of this
- Flux: [repository structure](https://fluxcd.io/flux/guides/repository-structure/) · [security](https://fluxcd.io/flux/security/) ·
  [end-to-end](https://fluxcd.io/flux/flux-e2e/)
