# AGENTS.md — `deploy/`

The Helm chart for the Hetzner platform and the Flux resources that deploy it. The nearest `AGENTS.md` wins, so this file overrides the repository root's for
anything under `deploy/`. [`charts/event-junkie/README.md`](charts/event-junkie/README.md) is written for a human deciding how to install the chart; this one
is for an agent about to change it. `infra/AGENTS.md` is the sibling, and its hazards are the opposite of these.

## The one rule that matters

**Everything that renders the chart is safe. Everything that installs it is not.**

Safe — pure functions of the working tree, no cluster, no kubeconfig, run them as often as you like:

```sh
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
helm template t deploy/charts/event-junkie --values deploy/charts/event-junkie/values-k3d.yaml
helm unittest --strict deploy/charts/event-junkie     # renders in-process; a plugin, not a cluster call
scripts/cluster-assertions.sh                         # the same suites against every HelmRelease's spec.values
```

- `helm unittest` is not installed by default: `helm plugin install https://github.com/helm-unittest/helm-unittest --version <HELM_UNITTEST_VERSION> --verify=false`.
  Pin what `validate-chart.yml` pins, for the plugin and for Helm itself (`HELM_VERSION`); a version that floats is a gate whose verdict floats, and
  `--strict` is where Helm 3 and 4 diverge most. **Trust the local failure when a hook and a check disagree.**
- The base `values.yaml` cannot render alone — `database.host` and `database.existingSecret` are `required`. Add
  `--set database.host=10.0.1.2 --set database.existingSecret=events-db` when rendering without an environment values file.

**Never run `helm install`, `helm upgrade`, `helm uninstall`, `helm rollback` or `helm test` on your own initiative.** They reach a real cluster; if a task
appears to require one, stop and say so. **`helm install --dry-run` is not safe either** — it resolves the current kubeconfig context and talks to that API
server. `--dry-run=client` does not; use it only when you need `NOTES.txt`, which `template` does not render.

**On an explicit, specific instruction you may install — but only against k3d**, and an instruction given once does not carry to the next session. Check
`kubectl config current-context`, stop if it is not `k3d-*`, and then **pass the context explicitly on every command, read-only ones included**:

```sh
helm --kube-context k3d-event-junkie install …
kubectl --context k3d-event-junkie get pods
flux --context k3d-event-junkie install      # flux resolves the current context too; a bare `flux check --pre` once reached an unrelated cluster
```

The developer kubeconfig holds other people's clusters, production ones among them, and `k3d cluster create` switches the active context as a side effect.
**Never write a context name you did not create into anything published** — commit message, issue, PR, comment, document. Those names belong to somebody's
other clients; #263 put four of them in a commit message that had to be rewritten. Say "an unrelated context"; only `k3d-*` names this project created belong here.

## What state this is in

The chart is deployed to staging and production through Flux, and exercised locally on k3d by `scripts/k3d-rehearsal.sh`. Staging follows `main` through
snapshot versions; production takes releases only, and serves a rehearsal hostname over a real certificate while `publish_dns` keeps the domain dark. What
has only met k3d is "installed and exercised locally", and what is unproven is the chart under real traffic. The rehearsal uses its **own database**
(`event_junkie_k3d`), never the development one, because installing the chart runs Flyway; [docs/DEVELOPMENT.md](../docs/DEVELOPMENT.md) is the runbook.

## Layout

```
deploy/
├── charts/event-junkie/
│   ├── Chart.yaml              apiVersion v2 — nothing needs v3, and whether Flux installs one is unverified; do not raise it to find out
│   ├── values.yaml             production-shaped; cannot render alone
│   ├── values-k3d.yaml         locally built images, drives k3d-rehearsal.sh up · values-k3d-images.yaml on top, K3D_IMAGES=1 only
│   ├── values.schema.json      required keys, enums, the importer's replica pin, `not: {required: [password]}`
│   ├── templates/              flat, one resource per file, kind in the filename
│   └── tests/                  helm-unittest suites — the only gate that catches a well-formed, schema-valid, wrong chart
└── clusters/                   what Flux reconciles, one directory per cluster
    ├── base/                   what staging and production both apply via `- ../base`; zero config differences between the two copies
    ├── staging/                the `flux bootstrap --path` target · prereleases admitted · cert-manager + Hetzner DNS-01 webhook
    ├── production/             releases only · cert-manager, HTTP-01, no webhook and no Hetzner token
    └── k3d/                    the rehearsal target with the *published* chart · `kubectl apply -k`, never bootstrapped · no cert-manager
```

**There is no `values-staging.yaml`, deliberately.** A `HelmRelease` cannot read a file from this repository, so each environment's configuration lives once,
under `spec.values` in its `helm-release.yaml`. `scripts/cluster-assertions.sh` extracts that and re-runs the chart's invariant suites against it, so the
assertions gate what Flux deploys. It follows that every assertion in `invariants_test.yaml`, `hardening_test.yaml`, `ingress_test.yaml` and
`importer_test.yaml` must hold under _any_ values file — one that names a host, a port or a database belongs in a test with its own `values:`.

**One chart, not three**, and three explicit Deployment templates rather than a loop over a `components` map: the frontend has no database and no JVM, the
importer has no ingress and a different strategy, so a generic template would be three-quarters conditionals.

## Conventions

Beyond the [Helm chart best practices guide](https://helm.sh/docs/chart_best_practices/): flat `templates/` with the kind in a dashed filename
(`bff-deployment.yaml`), one resource per file; namespaced template names (`event-junkie.fullname`); per-workload templates take a dict
(`include "event-junkie.labels" (dict "ctx" $ "component" "bff")`, where `component` is both the label value and the key under `.Values`); whitespace inside
the braces, two-space indent, chomp aggressively; camelCase values, no hyphens, strings quoted, maps over arrays where `--set` might reach; per-component
nesting (`bff.*`, `importer.*`, `frontend.*`) under the guide's own exception. A property gets a comment when it has something to say — a constraint, a
trade-off, a failure it avoids — never `requests.cpu is what the scheduler reserves` (#713). Comments explain why an obvious alternative was not taken, and
cross-references point at `docs/ops/PLATFORM_SETUP.md` sections and ADR numbers.

The audited API versions, the YAML boolean trap and the `restricted` Pod Security Standards position are a path-scoped rule,
[kubernetes.instructions.md](../.github/instructions/kubernetes.instructions.md), loaded with any `.yaml` under `deploy/`.

## Things that will bite

- **Selector labels are the immutable subset.** `helm.sh/chart` and `app.kubernetes.io/version` change every release; `spec.selector` cannot. Use
  `event-junkie.selectorLabels` for any selector and `event-junkie.labels` only for `metadata.labels`. Mixing them installs and fails the _second_ release;
  asserted for that reason. `app.kubernetes.io/component` **is** in the selector and must stay, or all three Deployments select each other's pods.
- **`SPRING_FLYWAY_USER`, not `SPRING_FLYWAY_USERNAME`** — the wrong spelling binds to nothing, silently. The importer holds R2DBC for the application and
  JDBC for Flyway; locally Docker Compose support supplies both, so forgetting the JDBC half in the chart means migrations never run, not a startup error.
- **`/api` lives in the BFF's controllers**, not in the ingress and not in `spring.webflux.base-path`. No Traefik `stripPrefix` middleware: ADR-012's
  portability argument is an image plus a Postgres URL, and a rewrite in a CRD is the first crack in it.
- **Actuator is private because it is on its own port.** Never add an ingress path for `/actuator`, never route the `management` port, never make the
  importer's Service `NodePort` or `LoadBalancer` — its admin API has no authentication; unroutability is what protects it.
- **The probes are one template and two meanings.** `event-junkie.jvmProbes` renders the same for both JVMs; each `application.yaml` decides what readiness
  contains (the BFF's includes `r2dbc` and `eventsSchema`, the importer's does not — ADR-018). Readiness `periodSeconds: 10, failureThreshold: 3` is the
  blip tolerance that decision rests on, not a default to tune, and **liveness must never gain a database indicator**: `startupProbe` watches the liveness
  path, so a first install would crash-loop at 30 × 5s.
- **`readOnlyRootFilesystem: true` needs writable mounts** — `/tmp` for the JVMs, `/var/cache/nginx` and `/var/run` for nginx, which fails at startup without them.
- **`importer.replicaCount: 1` and `strategy: Recreate` are ADR-008 correctness constraints.** Two schedulers means two concurrent imports of one source;
  the `RUNNING` check has no lock. The schema pins `const: 1`; raising it needs `SELECT … FOR UPDATE SKIP LOCKED` first, an ADR change.
- **No `namespace:` in any template's metadata** — it would silently win over Flux's `targetNamespace`. `.Release.Namespace` in a _reference_ is fine.
- **No `crds/` directory, ever.** Helm cannot upgrade or delete a CRD it installed. The chart renders _instances_ of cert-manager's and Traefik's kinds, and
  failing on an unknown kind when cert-manager is absent is correct.
- **`security.runAsUser` is 10001 and must match the images' `USER`**; `scripts/uid-consistency.sh` reads all three Dockerfiles against what the chart
  resolves per component, because `helm unittest` sees only the chart. A mismatch is a pod that cannot read its own files.
- **No floating tags, anywhere.** `image.tag` defaults to `""` and falls back to `.Chart.AppVersion`; the assertions reject `latest`, `head`, `canary`,
  `main`, `edge` and an untagged image.

## Flux

The decision is [ADR-016](../docs/adr/ADR-016_GITOPS_DELIVERY.md), the path is [RELEASING.md](../docs/ops/RELEASING.md), the bring-up order is
[CLUSTER_BOOTSTRAP.md](../docs/ops/CLUSTER_BOOTSTRAP.md). What bites when changing these files:

- **`flux-system/` is machine-written and machine-updated; never hand-edit it.** `flux bootstrap` writes `gotk-components.yaml` (~2 MB) and Renovate bumps
  its version strings without regenerating it (#1075), so every customisation — the SOPS `decryption` patch (#416), the Pod Security Admission labels (#604)
  — is a kustomize patch in `flux-system/kustomization.yaml`. Anything written into the generated file vanishes on the next bootstrap or bump.
  CLUSTER_BOOTSTRAP.md §9b has the `flux install --export` diff that proves a bump complete.
- **Bootstrap needs the org's deploy keys enabled** (fails at `422` otherwise), **pushes directly to `main`** (the ruleset goes off for two pushes), and wants
  the database and both Secrets to exist **first**, or the first reconcile installs a crash-looping importer.
- **The version range and the signature check live on the `OCIRepository`, not the `HelmRelease`.** With `chartRef` the release carries no version. Staging
  is `>=0.0.0-0` — the `-0` admits prereleases, and without it `no match found for semver: >=0.0.0`. Production adds
  `semverFilter: '^[0-9]+\.[0-9]+\.[0-9]+$'`, because excluding snapshots by omission is one careless `-0` from wrong. `spec.verify` (#1425) matches cosign's
  Fulcio certificate against `release.yml@refs/heads/main` or `@refs/tags/vX.Y.Z`; a chart pushed any other way reports
  `no signatures found` and the source keeps the last verified artifact. k3d copies staging's block verbatim.
- **The range only means "newest" if the versions order** (#455). Snapshots are `0.1.1-snapshot.<utc-timestamp>.g<sha>` because SemVer compares digits-only
  identifiers numerically and letter-bearing ones as ASCII; the old `g<sha>` scheme ran whichever sha sorted highest for three days while reporting `Ready`.
  `scripts/version-test.sh` fails if the timestamp goes.
- **`remediateLastFailure: true` on `upgrade`, deliberately not on `install`.** The default retries and then leaves the failed release running. On `install`
  remediation is an uninstall, and a failed first install left in place is what lets somebody read why.
- **No `Receiver`, permanently** — §8's firewall design has nothing inbound. Deploys land within one `interval`: 1m on staging's source, 10m on production's.
- **The repository is the control plane.** Both controllers are `cluster-admin`, so a push to `deploy/clusters/**` on `main` applies anything. Branch protection
  replaces the kubeconfig (#443).

## Third-party HelmReleases (#265)

cert-manager on both clusters, Hetzner's DNS-01 webhook and OpenObserve besides. Four rules, all of which fail quietly:

- **Pin the version exactly; never a range.** `scripts/cluster-assertions.sh` rejects anything but `X.Y.Z` or `vX.Y.Z`.
- **A release that renders a ClusterIssuer declares `dependsOn` cert-manager**, or the whole application release fails on a fresh cluster looking like a chart
  bug. Asserted.
- **Hetzner's own webhook (`cert-manager-webhook-hetzner` from `charts.hetzner.cloud`, `groupName` `acme.hetzner.com`, `tokenSecretKeyRef`), never a fork.**
  The old `dns.hetzner.com` API is gone; forks install, report Ready and fail at challenge time.
- **Bump staging before production, and expect to edit two files**: the `HelmRelease` exists per cluster, its `HelmRepository` is shared in `base/` (#953, #1080).

**The hcloud token DNS-01 needs is project-wide** — it could delete the servers. Staging only; production must not acquire one for a wildcard certificate.

## Never hand-edit the chart version, never pin an image tag or digest

`Chart.yaml`'s `version` and `appVersion` are placeholders, both `0.0.0`. `release.yml` computes one number from the release tags and the commits since the
last one (ADR-032) and stamps it into both, so a hand bump decides nothing; a local `helm install` from a checkout installs `0.0.0`, the honest number for an
unstamped chart. No change needs a version written anywhere — a `feat` in a product scope earns the minor by itself.

**No published values file may set `<component>.image.tag` or `.digest`.** The `""` default falls back to `.Chart.AppVersion`, and `release.yml` stamps each
image's digest into the chart it packages (#264, #1473) — that fallback is what keeps chart and images in step, and a pinned tag makes the render look _more_
correct while one image is not the one this build produced. `values-k3d.yaml` (`dev`) is the sole exception; `invariants_test.yaml` and
`cluster-assertions.sh` enforce it.

## Never put a credential in a values file

Not in `values.yaml`, not in an overlay, not behind a conditional, not "temporarily". **There is no inline-password path in this chart and adding one is the
change to refuse in review.** `database.existingSecret` names a Secret created out of band; `values.schema.json` carries `not: {required: [password]}` on
`database` so the wrong shape fails at install. The Hetzner DNS token is the same: the chart names the Secret and never creates it.

## The assertions are the point

`charts/event-junkie/tests/` catches a chart that is well-formed, schema-valid and wrong; `validate-chart.yml` and `/verify` run it. **Add an assertion
whenever you fix a bug in a template** — the failures worth guarding surface on the second release, or as absent data rather than an error.

- **`checksum/config` breaks a suite that does not list the ConfigMap.** The deployments `include (print $.Template.BasePath "/configmap.yaml")` and
  helm-unittest renders only what a suite lists: `no template "…/configmap.yaml" associated with template "gotpl"`.
- **An assertion applies to every document from every listed template.** Per-resource needs `documentSelector` or a per-test `templates:` (a subset of the
  suite's). A `template:` key _inside_ an assert is silently ignored.
- **`failedTemplate` needs no `templates:`** — Helm blames whichever template it reached first, so `required_values_test.yaml` has none.
- **Positive assertions over `**/*.yaml` fail on every document that legitimately lacks the path**, so `invariants_test.yaml` is negative and ends in scoped
  positive controls — negatives pass when their path expression breaks, and the controls notice. Add a control beside every new negative. Prefer a JSONPath
  filter (`env[?(@.name=="…")]`): one that matches nothing reports an unknown path, not a pass.
