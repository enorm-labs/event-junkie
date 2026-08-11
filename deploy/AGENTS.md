# AGENTS.md — `deploy/`

The Helm chart for the Hetzner platform. The nearest `AGENTS.md` wins, so this file overrides the repository root's for anything under `deploy/`. Read
[`charts/event-junkie/README.md`](charts/event-junkie/README.md) next to it — that one is written for a human deciding how to install the chart, this one for an
agent about to change it.

`infra/AGENTS.md` is the sibling document and the two are not interchangeable: the hazards there are the opposite of the ones here, which is why the rules were
not merged into one file.

## The one rule that matters

**Everything that renders the chart is safe. Everything that installs it is not.**

`helm lint`, `helm template` and `kubeconform` are pure functions of the working tree. They reach no cluster, need no kubeconfig, and cannot break anything —
run them as often as you like:

```sh
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-staging.yaml
helm template t deploy/charts/event-junkie --values deploy/charts/event-junkie/values-staging.yaml
deploy/scripts/render-assertions.sh
shellcheck -x deploy/scripts/*.sh
```

The base `values.yaml` cannot render on its own — `database.host` and `database.existingSecret` have no safe default and the helpers `required` them. Add
`--set database.host=10.0.1.2 --set database.existingSecret=events-db` when rendering without an environment values file.

**Never run `helm install`, `helm upgrade`, `helm uninstall`, `helm rollback` or `helm test` on your own initiative.** They reach a real cluster. If a task
appears to require one, stop and say so.

**`helm install --dry-run` is not in the safe list**, and this is the trap in that list: it resolves the current kubeconfig context and talks to that cluster's
API server for capability discovery. `--dry-run=client` does not, and `helm template` does not. Prefer `helm template`; reach for `--dry-run=client` only when
you specifically need `NOTES.txt` rendered, which `template` does not do.

**On an explicit, specific instruction you may install — but only against k3d.** Check the context first (`kubectl config current-context`) and stop if it is not
a `k3d-*` one. An instruction to install locally is not permission to touch staging, and an instruction given once does not carry to the next session.

## What state this is in

**The chart has never been installed anywhere, as of 2026-08-11.** It lints, renders under both Helm 3 and Helm 4, passes `kubeconform` against the Kubernetes
and CRD schemas, and satisfies every assertion in `render-assertions.sh`. None of that is evidence that a pod starts.

Three things are still missing and each is somebody else's issue: **the images do not exist** (#426 for the two backends, #262 for the frontend), **cert-manager
is not installed on any cluster** (#265), and **nothing has run `helm install`** (#263 is the k3d rehearsal, and it is the first time any of this executes).

Do not describe the chart as working, tested or deployed. "Written and statically validated" is the accurate phrase, and the same honesty `infra/AGENTS.md`
applies to `environments/` applies here.

## Layout

```
deploy/
├── charts/event-junkie/
│   ├── Chart.yaml            apiVersion v2 — see below
│   ├── values.yaml           production-shaped; cannot render alone
│   ├── values-staging.yaml   #265 · single node · DNS-01
│   ├── values-k3d.yaml       #263 · written blind, expected to change on first use
│   ├── values.schema.json    required keys, enums, and the importer's replica pin
│   └── templates/            flat, one resource per file, kind in the filename
└── scripts/
    └── render-assertions.sh  the only gate that catches a chart doing the wrong thing quietly
```

`deploy/` rather than a top-level `charts/` because #414 adds `deploy/clusters/` for Flux and those two belong next to each other. The GHCR path
(`oci://ghcr.io/enorm-labs/charts/event-junkie`, #264) is independent of the repository path.

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
- **camelCase values, no hyphens, strings quoted**, and every property carries a comment starting with the property's own name. Maps rather than arrays wherever
  `--set` might touch a value.
- **Per-component nesting** (`bff.*`, `importer.*`, `frontend.*`) rather than the guide's preference for flat, under its own stated exception for "a large
  number of related variables, at least one non-optional".
- **Comments explain why**, and specifically why an obvious alternative was not taken — why `/api` is not a Traefik middleware, why the ClusterIssuer is off by
  default, why there is no `crds/` directory. Match that. Do not add comments that restate the YAML.
- Cross-references point at `docs/PLATFORM_SETUP.md` sections and ADR numbers, as in `infra/`. Keep them.

## Things that will bite

- **`apiVersion: v2` in `Chart.yaml` is not a leftover.** The local Helm binary is v4, but Flux's helm-controller embeds the Helm **3** SDK. Raising it to v3
  would render fine locally and produce a chart Flux cannot install — and that would not surface until #414. CI pins a Helm 3 client for this reason.
- **Selector labels are the immutable subset.** `spec.selector` is immutable after creation, and `helm.sh/chart` and `app.kubernetes.io/version` change on every
  release. Use `event-junkie.selectorLabels` for any selector and `event-junkie.labels` only for `metadata.labels`. Mixing them installs perfectly and then
  fails every subsequent upgrade — a failure nobody sees until the *second* release, which is why an assertion exists for it rather than a comment.
  `app.kubernetes.io/component` **is** in the selector and must stay: without it all three Deployments select each other's pods.
- **`SPRING_FLYWAY_USER`, not `SPRING_FLYWAY_USERNAME`.** The Spring property is `spring.flyway.user`. The wrong spelling binds to nothing and fails silently.
- **The importer holds two connection configurations for one database** — R2DBC for the application, JDBC for Flyway. Locally Spring Boot's Docker Compose
  support supplies both and nothing in `application.yaml` sets a URL, so this is invisible until there is no compose file. Forgetting the JDBC half means
  migrations never run; it is not a startup error.
- **`/api` lives in `spring.webflux.base-path`, not in the ingress.** There is no rewrite anywhere in the chart. Do not add a Traefik `Middleware` doing
  `stripPrefix` — ADR-012's portability argument is that the application is a Docker image plus a Postgres URL, and a rewrite in one controller's CRD is the
  first crack in it.
- **Actuator is private because it is on its own port**, not because an ingress rule excludes it. Never add an ingress path for `/actuator`, never route the
  `management` port, and never change the importer's Service to `NodePort` or `LoadBalancer` — its admin API has no authentication of its own, and what keeps
  it private is that nothing outside the cluster can address it.
- **`readOnlyRootFilesystem: true` needs writable mounts.** The JVM services need `/tmp`; nginx needs `/var/cache/nginx` and `/var/run` and fails at *startup*
  without them. Adding a workload means adding its mounts.
- **`importer.replicaCount: 1` is an ADR-008 correctness constraint, not a cost one**, and so is `strategy: Recreate`. Two schedulers means two concurrent
  imports of the same source; the `RUNNING` check is a read-then-write with no lock. `values.schema.json` pins the replica count with `const: 1`. Raising it
  needs `SELECT … FOR UPDATE SKIP LOCKED` first, which is an ADR change rather than a values change.
- **No `namespace:` in any template's metadata.** Flux's `HelmRelease` sets `targetNamespace` and a hardcoded namespace would silently win over it.
  `.Release.Namespace` in a *reference* — the Traefik middleware annotation needs one — is fine and follows `targetNamespace` correctly.
- **The chart ships no `crds/` directory and must not gain one.** Helm has no story for upgrading or deleting CRDs a chart installed, so owning cert-manager's
  or Traefik's is how a chart acquires a resource it can never safely change. The chart renders only *instances* of their types, and `helm install` failing on
  an unknown kind when cert-manager is absent is the correct behaviour, not a bug to work around.
- **`security.runAsUser` must match the UID the images actually run as.** It is 1000 today because #426 will build them that way; a distroless `nonroot` base
  would be 65532. A mismatch is a pod that cannot read its own files, which does not look like a values problem.
- **No floating tags, anywhere.** `image.tag` defaults to `""` and falls back to `.Chart.AppVersion`. The assertions reject `latest`, `head`, `canary`, `main`
  and `edge`, and an image with no tag at all.

## Never put a credential in a values file

Not in `values.yaml`, not in an environment overlay, not guarded behind a conditional, not "temporarily". **There is no inline-password path in this chart and
adding one is the change to refuse in review** — a values key that *can* hold a password is a key that eventually does, in a file that gets committed.

`database.existingSecret` names a Secret created out of band. SOPS-managed Secrets arrive in #416. `values.schema.json` carries a `not: {required: [password]}`
on the `database` object so the wrong shape fails at install time rather than in review.

The same applies to the Hetzner DNS token the DNS-01 solver needs: the chart names the Secret and never creates it.

## The assertions are the point

`deploy/scripts/render-assertions.sh` catches what `helm lint` and `kubeconform` structurally cannot: a chart that is well-formed, schema-valid and wrong. It
runs in `.github/workflows/validate-chart.yml` and under `/verify` on any diff touching `deploy/`.

**Add an assertion whenever you fix a bug in a template.** The failures worth guarding here mostly do not appear on first install — the selector-label trap
surfaces on the second release, a missing Flyway URL surfaces as absent data rather than an error.

Assertions are phrased as queries that return nothing when the chart is correct. That means a query broken by a rename looks like a pass, so the ones that could
silently match nothing are paired with an `assert_nonempty` guard. Keep that pairing when adding one.
