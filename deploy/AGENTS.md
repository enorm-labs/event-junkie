# AGENTS.md — `deploy/`

The Helm chart for the Hetzner platform. The nearest `AGENTS.md` wins, so this file overrides the repository root's for anything under `deploy/`. Read
[`charts/event-junkie/README.md`](charts/event-junkie/README.md) next to it — that one is written for a human deciding how to install the chart, this one for an
agent about to change it.

`infra/AGENTS.md` is the sibling document and the two are not interchangeable: the hazards there are the opposite of the ones here, which is why the rules were
not merged into one file.

## The one rule that matters

**Everything that renders the chart is safe. Everything that installs it is not.**

**The pre-commit `helm lint` and CI's run the same Helm.** Local hooks run whatever `helm` is installed — currently v4 — and `validate-chart.yml` pins
**v4.2.4**, the SDK helm-controller embeds. Keep them matched: a client difference here is invisible until it lets something through, and `--strict` is where
the versions diverge most (Helm 4 rejects an unknown `Chart.yaml` key, Helm 3 does not).

**Trust the local failure when a hook and a check disagree.** That rule is older than the matching pins and outlives them.

`helm lint`, `helm template` and `flux schema validate` are pure functions of the working tree. They reach no cluster, need no kubeconfig, and cannot break anything —
run them as often as you like:

```sh
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
helm template t deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
helm unittest --strict deploy/charts/event-junkie
scripts/cluster-assertions.sh
```

**`helm unittest` belongs in that safe list and needs saying explicitly**, because a plugin that runs under a `helm` subcommand looks like it might reach a
cluster and does not: it renders the chart in-process and asserts on the result. No kubeconfig, no context, nothing to get wrong. It is not installed by
default:

```sh
helm plugin install https://github.com/helm-unittest/helm-unittest --version v1.1.2 --verify=false
```

`--verify=false` is Helm 4's doing — it refuses an unverifiable plugin source without it, and the local binary is v4. CI pins Helm 3 and installs the same
version without the flag. Pin whatever `HELM_UNITTEST_VERSION` in `.github/workflows/validate-chart.yml` pins; a plugin whose version floats is a gate whose
verdict floats.

The base `values.yaml` cannot render on its own — `database.host` and `database.existingSecret` have no safe default and the helpers `required` them. Add
`--set database.host=10.0.1.2 --set database.existingSecret=events-db` when rendering without an environment values file.

**Never run `helm install`, `helm upgrade`, `helm uninstall`, `helm rollback` or `helm test` on your own initiative.** They reach a real cluster. If a task
appears to require one, stop and say so.

**`helm install --dry-run` is not in the safe list**, and this is the trap in that list: it resolves the current kubeconfig context and talks to that cluster's
API server for capability discovery. `--dry-run=client` does not, and `helm template` does not. Prefer `helm template`; reach for `--dry-run=client` only when
you specifically need `NOTES.txt` rendered, which `template` does not do.

**On an explicit, specific instruction you may install — but only against k3d.** Check the context first (`kubectl config current-context`) and stop if it is not
a `k3d-*` one. An instruction to install locally is not permission to touch staging, and an instruction given once does not carry to the next session.

**Checking once is not enough — pass the context explicitly on every command**, including read-only ones:

```sh
helm --kube-context k3d-event-junkie install …
kubectl --context k3d-event-junkie get pods
flux --context k3d-event-junkie install
```

**`flux` belongs in that list and it is easy to forget**, because most of its subcommands read rather than write. It resolves the current kubeconfig context
exactly like `helm install --dry-run` does — running a bare `flux check --pre` while writing #414 reached an unrelated cluster and failed against it, which is the
harmless version of the same mistake.

This is not hypothetical. During #263 the active context on the developer machine belonged to an unrelated project, and several other clusters — production ones
among them — were in the same kubeconfig. Assume that is the normal case rather than an unlucky one. `k3d cluster create` also switches the active context as a
side effect, so a bare `kubectl` inherits whatever is current at that moment rather than whatever you checked earlier.

**And never write a context name you did not create into anything published.** Not into a commit message, an issue, a pull request, a code comment or a
document. A kubeconfig on a working machine is a list of somebody's other clients and employers, and the cluster names in it are theirs, not this project's —
`kubectl config get-contexts`, `current-context` and `cluster-info` all print them, and they read as harmless context until they are on a public repository. This
rule exists because #263 put four of them in a commit message and they had to be rewritten out of the branch afterwards. Refer to "an unrelated context" and move
on; the only names that belong here are `k3d-*` ones this project created.

## What state this is in

**Installed and exercised on k3d as of 2026-08-12 (#263); never on a real cluster.** The chart was installed, upgraded across a version bump, `helm test`-ed and
uninstalled on a local k3d cluster with **arm64** nodes — the same architecture the Hetzner nodes will run. The full stack came up, the ingress split routed
correctly, and a real scrape reached `/api/events` through Traefik.

**Flux was added to that on 2026-08-12 (#414)** — the published chart reconciled from GHCR on k3d, `helm test` run in-cluster, and a deliberately broken release
rolled back.

**Deployed to the real staging cluster on 2026-08-13 (#424, #265).** `flux bootstrap` has now run against Hetzner: the Flux manifests are committed to
`deploy/clusters/staging/flux-system/`, cert-manager and the DNS-01 webhook installed ahead of the application through `dependsOn`, all three workloads came up,
Flyway applied its migrations against PostgreSQL over the private network, and the chart's `helm test` hook passed in-cluster. The runbook is
[docs/ops/CLUSTER_BOOTSTRAP.md](../docs/ops/CLUSTER_BOOTSTRAP.md).

**Most of the k3d gap is now closed — but say which parts.** TLS, cert-manager, DNS, git-sync, a real private-network database and genuine resource pressure are
all exercised on staging. **Production now runs the chart too**, dark: it serves a rehearsal hostname over a real Let's Encrypt certificate while `publish_dns`
keeps the domain unresolvable. Both environments are x86, because `cax21` cannot be bought in `eu-central` (#424).

**Staging follows `main` again as of #455**, having been pinned to a fixed tag while snapshot versions were unordered. The `OCIRepository` is back on
`semver: ">=0.0.0-0"`. That it _does_ move is the part still to be confirmed on the cluster after the first post-#455 merge publishes a chart — the ordering
itself is asserted in CI by `scripts/version-test.sh`.

So: the chart may now be described as **deployed to production**. What is still unproven is the chart under real traffic, and "installed and exercised locally" remains the accurate
phrase for anything that has only met k3d.

The runbook for re-running the rehearsal is in `docs/DEVELOPMENT.md`. Two things it will not let you skip: the rehearsal uses its **own database**
(`event_junkie_k3d`), never the local development one, because installing the chart runs Flyway; and every cluster command carries an explicit
`--kube-context k3d-…` (below).

## Layout

```
deploy/
├── charts/event-junkie/
│   ├── Chart.yaml            apiVersion v2 — see below
│   ├── values.yaml           production-shaped; cannot render alone
│   ├── values-k3d.yaml       #263 · locally built images; two of its blind guesses were wrong
│   ├── values-k3d-images.yaml  the image path, on top of the file above · K3D_IMAGES=1 only
│   ├── values.schema.json    required keys, enums, and the importer's replica pin
│   └── templates/            flat, one resource per file, kind in the filename
├── clusters/                 #414 · what Flux reconciles, one directory per cluster
│   ├── base/                 #953 · what staging and production both apply, via `- ../base`
│   │                         admission rule: zero config differences between the two copies
│   ├── staging/              the `flux bootstrap --path` target · snapshots · prereleases ADMITTED
│   │                         + cert-manager and the Hetzner DNS-01 webhook (#265)
│   ├── production/           releases only · SUSPENDED until #424 provisions a cluster
│   │                         + cert-manager, HTTP-01, no webhook and no Hetzner token
│   └── k3d/                  the rehearsal target · applied with `kubectl apply -k`, never bootstrapped
│                             no cert-manager: `tls.enabled: false`, so nothing references an issuer
└── charts/event-junkie/tests/   #430 · helm-unittest suites — the only gate that catches a chart
                                 doing the wrong thing quietly. `/tests/`, anchored, in .helmignore
```

There is no `deploy/scripts/` any more. `render-assertions.sh` became the suites above in #430, and the two things it checked that a chart test suite
structurally cannot — relationships between the files under `clusters/`, and rendering the chart with each `HelmRelease`'s `spec.values` — moved to
`scripts/cluster-assertions.sh` at the repository root.

`deploy/` rather than a top-level `charts/` because the chart and the Flux resources that deploy it belong next to each other. The GHCR path
(`oci://ghcr.io/enorm-labs/charts/event-junkie`, #264) is independent of the repository path.

**There is no `values-staging.yaml`, and that is deliberate (#414).** A `HelmRelease` cannot read a file from this repository, so staging's configuration lives
in `deploy/clusters/staging/helm-release.yaml` under `spec.values` — the single place it exists. Keeping a values file _as well_ would mean two copies that must
agree with nothing checking that they do, which is how an environment drifts. `scripts/cluster-assertions.sh` extracts `spec.values` from every HelmRelease and
re-runs the chart's invariant suites against it, so the assertions gate exactly what Flux deploys rather than a file nothing deploys. It is why every assertion
in `invariants_test.yaml`, `hardening_test.yaml`, `ingress_test.yaml` and `importer_test.yaml` must hold under _any_ values file — an assertion that hardcodes a
host or a port belongs in a test that names its own values, not in those four.

`values-k3d.yaml` survives because it is not a duplicate of anything: it drives `k3d-rehearsal.sh up`, which installs the _working tree's_ chart with images
built seconds ago. `deploy/clusters/k3d/` answers a different question with the _published_ chart and images. Both are worth having; neither substitutes for the
other.

**One chart, not three.** The three workloads share an ingress, a hostname, a database and a release lifecycle; subcharts would buy independent versioning
nobody wants. And three explicit Deployment templates rather than one generic loop over a `components` map: the frontend has no database and no JVM, the
importer has no ingress and a different strategy, so the generic template would be three-quarters conditionals.

## Conventions

Beyond the [Helm chart best practices guide](https://helm.sh/docs/chart_best_practices/), which is followed throughout:

- **Flat `templates/` with the kind in a dashed filename** — `bff-deployment.yaml`, `ingress.yaml`. One resource per file. A reviewer expects to find
  `ingress.yaml` by name.
- **Namespaced template names** — `event-junkie.fullname`, never a bare `fullname`.
- **Per-workload templates take a dict**, not the root context: `include "event-junkie.labels" (dict "ctx" $ "component" "bff")`. `component` is both the label
  value and the key under `.Values` holding that workload's settings.
- **Whitespace inside the braces** (`{{ .Values.x }}`), two-space indent, chomp aggressively.
- **camelCase values, no hyphens, strings quoted.** Maps rather than arrays wherever `--set` might touch a value. **A property gets a comment when it has
  something to say** — a constraint, a trade-off, a failure it avoids — and not otherwise. A blanket "every property carries one" buys nine lines of
  `nodeSelector constrains scheduling` and six of `requests.cpu is what the scheduler reserves`: restatement mandated by convention, which is the exact thing
  the next bullet forbids (#713). Where a comment could be read against more than one key, name the one it applies to.
- **Per-component nesting** (`bff.*`, `importer.*`, `frontend.*`) rather than the guide's preference for flat, under its own stated exception for "a large
  number of related variables, at least one non-optional".
- **Comments explain why**, and specifically why an obvious alternative was not taken — why `/api` is not a Traefik middleware, why the ClusterIssuer is off by
  default, why there is no `crds/` directory. Match that. Do not add comments that restate the YAML.
- Cross-references point at `docs/ops/PLATFORM_SETUP.md` sections and ADR numbers, as in `infra/`. Keep them.

## Kubernetes' own good practices, audited

**Moved to a path-scoped rule: [.github/instructions/kubernetes.instructions.md](../.github/instructions/kubernetes.instructions.md).** The audited API
versions, the annotation that survives rendering, the YAML boolean trap, the two deliberate deviations and the `restricted` Pod Security Standards position
load with any `.yaml` under `deploy/`, and reach Copilot on their own rather than through this file.

## Things that will bite

- **`apiVersion: v2` in `Chart.yaml` is not a leftover**, but the reason changed with #1006. It used to be that helm-controller embedded the Helm **3** SDK
  and could not install a v3 chart. It embeds Helm 4, so that is no longer the argument. Keep v2 because **nothing here needs a v3 feature**, and because
  whether Flux installs a v3 chart is now unverified rather than known-false. Do not raise it to find out.
- **Selector labels are the immutable subset.** `spec.selector` is immutable after creation, and `helm.sh/chart` and `app.kubernetes.io/version` change on every
  release. Use `event-junkie.selectorLabels` for any selector and `event-junkie.labels` only for `metadata.labels`. Mixing them installs perfectly and then
  fails every subsequent upgrade — a failure nobody sees until the _second_ release, which is why an assertion exists for it rather than a comment.
  `app.kubernetes.io/component` **is** in the selector and must stay: without it all three Deployments select each other's pods.
- **`SPRING_FLYWAY_USER`, not `SPRING_FLYWAY_USERNAME`.** The Spring property is `spring.flyway.user`. The wrong spelling binds to nothing and fails silently.
- **The importer holds two connection configurations for one database** — R2DBC for the application, JDBC for Flyway. Locally Spring Boot's Docker Compose
  support supplies both and nothing in `application.yaml` sets a URL, so this is invisible until there is no compose file. Forgetting the JDBC half means
  migrations never run; it is not a startup error.
- **`/api` lives in the BFF's controllers, not in the ingress and not in `spring.webflux.base-path`.** There is no rewrite anywhere in the chart, and nothing
  sets that property. Do not add a Traefik `Middleware` doing
  `stripPrefix` — ADR-012's portability argument is that the application is a Docker image plus a Postgres URL, and a rewrite in one controller's CRD is the
  first crack in it.
- **Actuator is private because it is on its own port**, not because an ingress rule excludes it. Never add an ingress path for `/actuator`, never route the
  `management` port, and never change the importer's Service to `NodePort` or `LoadBalancer` — its admin API has no authentication of its own, and what keeps
  it private is that nothing outside the cluster can address it.
- **The probes are one template and two meanings, and `_helpers.tpl` cannot show you which.** `event-junkie.jvmProbes` renders identically for the BFF and the
  importer; what `/actuator/health/readiness` _contains_ is decided in each service's `application.yaml`. The BFF's readiness group includes `r2dbc` and
  `eventsSchema` so a pod that cannot reach the database or its schema leaves the Service; the importer's does not, because nothing routes to it and taking its
  admin API away during a database incident removes the tool an operator needs. ADR-018 argues both. Two things follow for anyone editing this file: the
  readiness probe's `periodSeconds: 10, failureThreshold: 3` is the **blip tolerance** that decision depends on and is not a default to tune, and **liveness
  must never gain a database indicator** — the `startupProbe` watches the liveness path, so a database-dependent liveness group turns a first install into a
  crash-loop at 30 × 5s.
- **`readOnlyRootFilesystem: true` needs writable mounts.** The JVM services need `/tmp`; nginx needs `/var/cache/nginx` and `/var/run` and fails at _startup_
  without them. Adding a workload means adding its mounts.
- **`importer.replicaCount: 1` is an ADR-008 correctness constraint, not a cost one**, and so is `strategy: Recreate`. Two schedulers means two concurrent
  imports of the same source; the `RUNNING` check is a read-then-write with no lock. `values.schema.json` pins the replica count with `const: 1`. Raising it
  needs `SELECT … FOR UPDATE SKIP LOCKED` first, which is an ADR change rather than a values change.
- **No `namespace:` in any template's metadata.** Flux's `HelmRelease` sets `targetNamespace` and a hardcoded namespace would silently win over it.
  `.Release.Namespace` in a _reference_ — the Traefik middleware annotation needs one — is fine and follows `targetNamespace` correctly.
- **The chart ships no `crds/` directory and must not gain one.** Helm has no story for upgrading or deleting CRDs a chart installed, so owning cert-manager's
  or Traefik's is how a chart acquires a resource it can never safely change. The chart renders only _instances_ of their types, and `helm install` failing on
  an unknown kind when cert-manager is absent is the correct behaviour, not a bug to work around.
- **`security.runAsUser` must match the UID the images actually run as, and `scripts/uid-consistency.sh` is what makes that a gate rather than a comment.** It
  is **10001** since #448 — above 10000, so it cannot collide with an account in the host's own user range; a distroless `nonroot` base would be 65532, which
  also clears the bar. A mismatch is a pod that cannot read its own files, which does not look like a values problem. The check reads the `USER` line out of
  all three Dockerfiles and compares it with what the chart resolves per component, including a `<component>.runAsUser` override; `helm unittest` can only see
  the chart, so it catches the chart drifting from itself and never the image moving underneath it.
- **No floating tags, anywhere.** `image.tag` defaults to `""` and falls back to `.Chart.AppVersion`. The assertions reject `latest`, `head`, `canary`, `main`
  and `edge`, and an image with no tag at all.

## Flux: what the k3d rehearsal proved, and the two traps it found

The decision and its consequences are [ADR-016](../docs/adr/ADR-016_GITOPS_DELIVERY.md); the end-to-end path is [docs/ops/RELEASING.md](../docs/ops/RELEASING.md). What
follows is only what bites when changing these files.

**Exercised on k3d as of 2026-08-12 (#414)** — `flux install`, the real chart pulled from GHCR, all three workloads on published images, `helm test` green, and a
deliberately broken release rolled back.

**`flux bootstrap` ran for real on 2026-08-13**, against staging, and added a `flux-system/` directory to `deploy/clusters/staging/` — machine-written, ~2 MB,
never hand-edited. Three things it taught that no amount of reading would have: the org must have **deploy keys enabled** (a policy, not a token scope, and it
fails at `422`); bootstrap **pushes directly to `main`**, which the branch ruleset forbids, so the ruleset has to be off for two pushes and back on immediately
after; and the whole flow wants the database and both secrets to exist **first**, or the first reconcile installs a crash-looping importer.
[docs/ops/CLUSTER_BOOTSTRAP.md](../docs/ops/CLUSTER_BOOTSTRAP.md) has the order.

**That directory is now machine-_updated_ as well as machine-written.** Renovate watches
`gotk-components.yaml` and opens a pull request when Flux releases, regenerating the manifest rather
than editing versions inside it (#384, ADR-024). **This is safe only because of where customisations
live:** the SOPS `decryption` patch (#416) and the Pod Security Admission labels (#604) are kustomize
patches in `flux-system/kustomization.yaml`, never edits to the generated file — which is exactly
what both patches say in their own comments. **Keep it that way.** A customisation written into
`gotk-components.yaml` now has two ways to vanish silently: a `flux bootstrap` re-run, and any
Renovate upgrade. And when reviewing one of those pull requests, honour the PSA patch's own
instruction to re-check `restricted` against the regenerated controllers.

**The version range lives on the `OCIRepository`, not the `HelmRelease`.** With `chartRef` the release carries no version at all — `spec.ref.semver` on the source
decides everything. Staging uses `>=0.0.0-0`; the `-0` is what admits prereleases, and without it the range matches no snapshot at all. Observed rather than
assumed: removing it gives `no match found for semver: >=0.0.0`. Production uses `semverFilter: '^[0-9]+\.[0-9]+\.[0-9]+$'` as well as a range, because excluding
snapshots _by omission_ is one careless `-0` away from being wrong.

**And the range only means "newest" if the versions order** (#455). The `-0` picks the candidates; the version scheme picks the winner. Snapshots are
`0.1.1-snapshot.<utc-timestamp>.g<sha>` because SemVer compares a digits-only identifier numerically and a letter-bearing one lexically in ASCII — the previous
`g<sha>` scheme sorted by short sha, so staging silently ran whichever sha sorted highest for three days while reporting `Ready`. Do not "simplify" the timestamp
out. `scripts/version-test.sh` fails if you do.

**`remediateLastFailure` defaults to false, so a bad deploy is retried and then left broken.** Remediation runs _between_ attempts and never after the final one —
exhaust the retries and the cluster keeps running the release that failed. Every `HelmRelease` here sets `remediateLastFailure: true` on `upgrade` for that reason.
It is deliberately **not** set on `install`, where remediation is an uninstall and there is no previous version to return to: leaving a failed first install in
place is what lets somebody look at why it failed. This was found by breaking a release on purpose and watching the workload stay broken — no amount of reading
the manifest would have shown it.

**Receivers are ruled out permanently**, so reconciliation is polled rather than pushed. A `Receiver` is an inbound HTTP endpoint and §8's firewall design exists
to have nothing inbound. Deploys therefore land within about one `interval`, and Flux's own guidance is not to poll below 30s without webhooks — hence 1m on
staging's source, 10m on production's.

**The repository is now the control plane.** `kustomize-controller` and `helm-controller` are bound to `cluster-admin`, so anyone who can push to
`deploy/clusters/**` on `main` can have the cluster apply anything. What Flux removes is CI holding a _credential_; it does not remove the power, it relocates it.
Branch protection is the control that replaces the kubeconfig — see #443.

## Third-party HelmReleases (#265)

Since #265 the cluster directories carry components this repository does not build: cert-manager on both clusters, and Hetzner's DNS-01 webhook on staging. Four
rules, all of them things that fail quietly rather than loudly.

- **Pin the version exactly; never a range.** `>=1.21 <1.22` lets an upstream release reach the cluster with no diff, no review and no commit — the property
  GitOps exists to remove. `scripts/cluster-assertions.sh` rejects anything that is not `X.Y.Z` or `vX.Y.Z`.
- **A release that renders a ClusterIssuer must declare `dependsOn`.** The chart's ClusterIssuer is a `cert-manager.io/v1` kind and the API server rejects
  unknown kinds, so without cert-manager the _whole_ application release fails — workloads included, on the first bootstrap of a new cluster, looking exactly
  like a bug in our chart. Asserted, so it cannot be dropped silently.
- **Use Hetzner's own DNS webhook, never a community fork.** The old `dns.hetzner.com` API was shut down in May 2026 and the forks still speak it; they install
  cleanly, report Ready, and fail at challenge time. Official chart: `cert-manager-webhook-hetzner` from `charts.hetzner.cloud`, `groupName`
  `acme.hetzner.com`, solver config `tokenSecretKeyRef`.
- **Bump staging before production, and expect to edit two files.** cert-manager's `HelmRelease` still exists once per cluster, because the two copies differ
  in what they do. Its `HelmRepository` does not: `base/helm-repository-jetstack.yaml` is shared, and one edit moves both (#953).

**The hcloud token DNS-01 needs is project-wide** — it cannot be scoped to a zone, so it could delete the servers. It exists on staging only, and production
must not acquire one to gain a wildcard certificate.

## Never hand-edit the chart version, and never pin an image tag

`Chart.yaml`'s `version` and `appVersion` are **placeholders**. `.github/workflows/release.yml` computes one number from `gradle.properties` and stamps it into
both before packaging, so bumping them by hand does not decide what gets published — it only creates drift, and `scripts/version.sh check` fails the build when
it does. Both must equal the base version `gradle.properties` declares (`0.3.1-SNAPSHOT` → `0.3.1`). If a change genuinely needs a new version, the bump belongs
in `gradle.properties`, `events-frontend/package.json` and both chart fields together.

**And no published values file may set `<component>.image.tag`.** The default `""` falls back to `.Chart.AppVersion`, and that fallback is the whole mechanism
keeping the chart and the images in step (#264). Pinning a tag opts one component out of it, and the render looks _more_ correct afterwards, not less — every
image carries a plausible tag, one of them just isn't the one this build produced. `values-k3d.yaml` is the sole exception (`dev`, never leaves a laptop);
`tests/invariants_test.yaml` enforces the chart's own default and `scripts/cluster-assertions.sh` enforces every HelmRelease.

## Never put a credential in a values file

Not in `values.yaml`, not in an environment overlay, not guarded behind a conditional, not "temporarily". **There is no inline-password path in this chart and
adding one is the change to refuse in review** — a values key that _can_ hold a password is a key that eventually does, in a file that gets committed.

`database.existingSecret` names a Secret created out of band. SOPS-managed Secrets arrive in #416. `values.schema.json` carries a `not: {required: [password]}`
on the `database` object so the wrong shape fails at install time rather than in review.

The same applies to the Hetzner DNS token the DNS-01 solver needs: the chart names the Secret and never creates it.

## The assertions are the point

`charts/event-junkie/tests/` catches what `helm lint` and `flux schema validate` structurally cannot: a chart that is well-formed, schema-valid and wrong. It runs
in `.github/workflows/validate-chart.yml` and under `/verify` on any diff touching `deploy/`. Five suites, twenty-three tests, five renders. They were
`deploy/scripts/render-assertions.sh` until #430.

**Add an assertion whenever you fix a bug in a template.** The failures worth guarding here mostly do not appear on first install — the selector-label trap
surfaces on the second release, a missing Flyway URL surfaces as absent data rather than an error.

**Four things to know before writing one**, all of which cost time to find:

- **`checksum/config` breaks a suite that does not list the ConfigMap.** `bff-deployment.yaml` and `importer-deployment.yaml` both do
  `include (print $.Template.BasePath "/configmap.yaml")`, and helm-unittest renders _only_ the templates a suite lists. Omit it and you get
  `no template "…/configmap.yaml" associated with template "gotpl"`, which points nowhere useful.
- **An assertion applies to every document from every listed template**, so anything per-resource needs `documentSelector` or a per-test `templates:` — and a
  per-test `templates:` must be a subset of the suite's. A `template:` key _inside_ an assert is not a scoping mechanism and is silently ignored; that one
  looked like it worked twice.
- **`failedTemplate` needs no `templates:` at all.** Helm attributes a `required` failure to whichever template it reached first, so a scoped suite asserts on
  where Helm happened to blame rather than on whether the chart refused. `required_values_test.yaml` deliberately has no `templates:` key anywhere.
- **Positive assertions over `**/*.yaml` fail on every document that legitimately lacks the path.** That is why `invariants_test.yaml` is entirely negative, and
  why it ends in a block of scoped positive controls. Negatives pass when their path expression breaks — the controls are what notice. Do not delete one, and
  add one alongside any new negative. Where a JSONPath filter will do the job (`env[?(@.name=="…")]`) prefer it: a filter that matches nothing is reported as an
  unknown path rather than as a pass, which is the vacuity problem solved rather than guarded against.

**And the suites in `charts/event-junkie/tests/` must hold under every values file**, because `scripts/cluster-assertions.sh` re-runs four of the five against
each cluster's `spec.values`. An assertion that names a host, a port or a database name belongs in a test that supplies its own `values:`.
