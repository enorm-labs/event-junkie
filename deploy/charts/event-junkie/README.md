# event-junkie Helm chart

Deploys `events-bff`, `events-importer` and `events-frontend` behind one Traefik ingress, with TLS
from cert-manager, and with the importer's admin API and every `/actuator/**` endpoint unreachable
from outside the cluster.

**Status: installed and exercised on k3d, never on a real cluster.** As of #263 (2026-08-12) the
chart has been installed, upgraded, tested and uninstalled on a local k3d cluster running **arm64**
nodes — the same architecture the Hetzner nodes will use. What that run proved, and what it did not:

| Proved | Still unproven |
|---|---|
| The full stack comes up: all three pods Ready, no restarts | Anything on a real cluster, or through a real ingress with TLS |
| Ingress routing — `/` → frontend, `/api` → BFF | cert-manager, ACME, DNS-01 (#265) |
| The importer's admin API and `/actuator` are unreachable through the ingress | NetworkPolicies and PSA (#416) |
| A real scrape reaching `/api/events` through Traefik | Flux reconciliation and rollback (#414) |
| **`helm upgrade` across a chart version bump** — the selector-immutability trap does not occur | Behaviour under load, or on a node with real resource pressure |
| `helm test` passes | |

`helm lint`, `helm template`, `kubeconform` and
[`../../scripts/render-assertions.sh`](../../scripts/render-assertions.sh) all pass as before, but
they are no longer the only evidence.

## Install

```sh
# 1. The database credentials, out of band. The chart never templates a password.
kubectl create secret generic events-db \
  --from-literal=username=events \
  --from-literal=password="$(…)"

# 2. cert-manager must already be installed (#265). `helm install` fails outright without it,
#    because the API server rejects an unknown kind — it is not a race that resolves itself.

helm install event-junkie deploy/charts/event-junkie \
  --namespace event-junkie --create-namespace \
  --values deploy/charts/event-junkie/values-staging.yaml
```

There is no `namespace:` in any template. Flux's `HelmRelease` sets `targetNamespace` (#414), and a
hardcoded namespace would silently win over it.

Released versions are also published as an OCI artifact, which is what Flux will consume:

```sh
helm install event-junkie oci://ghcr.io/enorm-labs/charts/event-junkie --version 0.1.0 …
```

**A checkout and a published chart are not interchangeable.** The version in `Chart.yaml` is a
placeholder that `release.yml` stamps over, so installing from a checkout gives you a chart claiming
a version whose images may not exist. Install from the OCI reference unless you are deliberately
testing local template changes — which is what `values-k3d.yaml` and its `dev` tags are for.

## Values

Only the values worth a decision are listed. Every property is documented in
[`values.yaml`](values.yaml) itself, next to its default.

| Key | Default | Notes |
|---|---|---|
| `database.host` | `""` | **Required.** `postgres_ip` from the matching `infra/environments/<env>` stack |
| `database.existingSecret` | `""` | **Required.** Name of a Secret that already exists. There is no inline-password path, guarded or otherwise |
| `database.secretKeys.username` / `.password` | `username` / `password` | Keys inside that Secret |
| `image.registry` | `ghcr.io` | Per component: `<component>.image.repository` and `.tag` |
| `<component>.image.tag` | `""` | Falls back to `.Chart.AppVersion`. Never `latest` — the render assertions fail the build on a floating tag |
| `bff.replicaCount` | `2` | Stateless and read-only |
| `bff.basePath` | `/api` | Served by Spring, not rewritten by the ingress — see below |
| `bff.service.managementPort` | `9001` | `/actuator/**`. No ingress rule names it |
| `importer.replicaCount` | `1` | **Pinned to 1 by `values.schema.json`.** ADR-008; see below |
| `frontend.service.port` | `8080` | Cannot be 80: nginx runs non-root and cannot bind a privileged port |
| `security.runAsUser` | `1000` | Must match the UID the images actually run as (#426) |
| `ingress.host` | `event-junkie.de` | The only name routed to the applications |
| `ingress.redirectHosts` | `[event-junkie.com]` | 301 to `ingress.host`, with its own certificate. Empty means nothing Traefik-specific renders at all |
| `certManager.clusterIssuer.create` | `false` | A ClusterIssuer is a cluster-scoped singleton — see below |
| `certManager.clusterIssuer.server` | ACME **staging** | Deliberately not production. 50 certificates per registered domain per week, and burning it costs seven days |
| `certManager.clusterIssuer.solver` | `http01` | `dns01` for staging, which has no public address for HTTP-01 to reach |

Three values files ship with the chart: `values.yaml` (production-shaped, and it cannot render on
its own — the two required keys have no safe default), `values-staging.yaml` (#265) and
`values-k3d.yaml` — no longer written blind: #263 ran it, and two values it had guessed were wrong (the database port and the database name, neither catchable by rendering).

## The parts that are not obvious from the templates

### `version` and `appVersion` are stamped, and they are the same number

Both are placeholders in the committed `Chart.yaml`. `.github/workflows/release.yml` computes one
version from `gradle.properties` and writes it into both before packaging, so the chart version, the
`appVersion` and all three image tags are the same string by construction rather than by discipline.

**`appVersion` is also the default image tag.** Every component's `image.tag` defaults to `""` and
falls back to `.Chart.AppVersion`, which is the entire mechanism keeping the chart and the images in
step — and it is one line away from being defeated. A published values file that pins
`<component>.image.tag` silently opts that component out: the render still looks correct, with a
plausible tag on every image, while one workload is pinned to a version nobody chose. Only
`values-k3d.yaml` sets tags (`dev`), and it never leaves a laptop;
[`../../scripts/render-assertions.sh`](../../scripts/render-assertions.sh) fails the build if
`values.yaml` or `values-staging.yaml` ever grows one.

The chart version does **not** move independently of the application. That would be right for a
public chart with many consumers; here the chart has one consumer and ships from the same commit as
the code it deploys, so a second number would be bookkeeping. `scripts/version.sh check` fails the
build if the two placeholders drift from `gradle.properties`. Full scheme in
[DEVELOPMENT.md](../../../docs/DEVELOPMENT.md#versions-and-cutting-a-release).

### `/api` is Spring's, not the ingress's

The BFF's controllers are mounted at the root — `/events`, `/venues`, `/artists`, `/promoters`,
`/genres`, `/meta`. The frontend's generated client prepends `/api`, and Vite's dev proxy strips it
locally. So something has to reconcile the two in a cluster.

This chart sets `SPRING_WEBFLUX_BASE_PATH=/api` rather than adding a Traefik `Middleware` doing
`stripPrefix`. The routing rule and the application then agree on the path and no rewrite exists
anywhere. ADR-012's portability argument is "a Docker image plus a Postgres URL"; a rewrite that
lives in one ingress controller's CRD is the first crack in that, whereas `base-path` travels with
the image.

**Confirmed 2026-08-11**, running the image built in #426 with `SPRING_WEBFLUX_BASE_PATH=/api`, so
this is no longer on #263's list to verify:

| Path | Result |
|---|---|
| `/api/events`, `/api/meta` | `200` |
| `/events` | `404` — nothing is served un-prefixed |
| `/api/v3/api-docs` | `200` |
| `/v3/api-docs` | `404` |

So the generated OpenAPI moves with the base path, while `events-frontend`'s `schema.ts` is
generated locally from the un-prefixed application. That is fine — the paths in the generated client
are relative and the client prepends `/api` — but it does mean the two are produced from different
URLs, which is worth knowing before someone "fixes" one of them.

### Actuator is private because of the port, not because of a rule

`management.server.port: 9001` on both JVM services. The Service publishes both ports, the Ingress
routes only the application one, and `/actuator/**` is therefore *unroutable* rather than merely
unrouted. This is what the BFF's own `application.yaml` asked for. It is set here rather than in
`application.yaml` so local development and the existing tests keep their single-port behaviour.

**Confirmed 2026-08-11** against the real image: with `MANAGEMENT_SERVER_PORT=9001`,
`/actuator/health` returns `404` on the application port and `200` on 9001 — and the webflux base
path does **not** move it, so it stays at `/actuator/…` rather than `/api/actuator/…`. Both halves
matter: the first is what makes it private, the second is what keeps the probe paths in
`_helpers.tpl` correct.

The same reasoning covers the importer: it has no Ingress backend anywhere in the chart, so
`/api/admin/**` is unreachable from outside the cluster rather than merely undocumented.

### Flyway is JDBC while the applications are R2DBC

The importer holds **two** connection configurations for one database. Locally Spring Boot's Docker
Compose support supplies both and nothing in `application.yaml` sets a URL, so this is invisible
until there is no compose file. The JDBC half is the one that gets forgotten, and its failure mode
is a migration that never runs rather than a startup error.

The property is `spring.flyway.user` — so `SPRING_FLYWAY_USER`, **not** `SPRING_FLYWAY_USERNAME`.
The wrong spelling binds to nothing and fails silently. Both are asserted in
`render-assertions.sh` for that reason.

### The BFF does *not* crash-loop on a first install — it does something worse

**Corrected 2026-08-12 by #263, the first time this chart was ever installed.** This section used to
say the BFF crash-loops for up to ninety seconds on an empty database while the importer migrates,
and that this was expected. Measured on k3d, that is simply not what happens:

| Event | Time |
|---|---|
| BFF reports **Ready** | `07:55:59` |
| Flyway creates the schema | `07:56:00.451` |

The BFF was Ready **about 1.2 seconds before the schema existed**, with zero restarts. Its readiness
group contains no database indicator — `/actuator/health/readiness` returns `{"status":"UP"}` with no
components — so readiness reflects only that Spring started, not that the BFF can serve.

That is worse than crash-looping, because a crash-looping pod receives no traffic while a Ready one
does. The window is ~1 second here, with one migration against a local Postgres; on a cold database
with more migrations it is longer, and during it Kubernetes will route requests to a BFF whose
queries fail.

The design decision stands — no init container and no Helm hook ordering the two, because a startup
dependency has to stay correct forever and Kubernetes already retries. What was wrong was the
description. Making readiness mean "can serve" is tracked separately; it is a probe-semantics change,
not a chart fix.

### One replica for the importer is correctness, not cost

Two schedulers means two concurrent imports of the same source. The 60-second tick skips sources
marked `RUNNING`, but that check is a read-then-write with no lock. `values.schema.json` pins
`importer.replicaCount` to `1` so raising it fails at install time; `strategy: Recreate` closes the
same hole during a deploy, when a rolling update would briefly run two pods.
`SELECT … FOR UPDATE SKIP LOCKED` is the prerequisite for relaxing either, and that is an ADR-008
change rather than a values change.

### Read-only root filesystems need writable mounts

`readOnlyRootFilesystem: true` everywhere, which means every path a process writes to has to be an
explicit `emptyDir`. All three workloads get exactly one: `/tmp`. The JVM services need it for
hsperfdata and anything a library assumes; nginx needs it because #262's base image
(`nginxinc/nginx-unprivileged`) puts its pid file and every temp path there rather than in
`/var/cache/nginx`, and symlinks its logs to stdout and stderr.

**Verified by running the images, not by reading their Dockerfiles** — all three start under
`--read-only --tmpfs /tmp --user 1000:1000`. An earlier revision of this chart also mounted
`/var/cache/nginx` and `/var/run`, which the stock `nginx` image would need and this one does not.
If the base image changes, re-check before assuming: the failure is at startup, not at first
request.

### The chart's labels are not `infra/`'s labels

Kubernetes objects here carry `app.kubernetes.io/{name,instance,component,version,part-of}`,
`helm.sh/chart` and `app.kubernetes.io/managed-by`. The OpenTofu resources in `infra/` carry
`environment`, `managed-by` and `project`. Same idea, different namespace, no relationship — do not
try to unify them.

`spec.selector` carries only the subset that never changes: `name`, `instance` and `component`.
`helm.sh/chart` and `app.kubernetes.io/version` change on every release, and `spec.selector` is
immutable after creation — mixing them produces a chart that installs perfectly and then fails every
subsequent upgrade with an immutable-field error, which nobody sees until the *second* release.

### cert-manager's CRDs are cert-manager's

The chart ships no `crds/` directory and creates no `Certificate`: the ingress-shim annotation makes
cert-manager create it. Helm has no story for upgrading or deleting CRDs a chart installed, so owning
somebody else's is how a chart acquires a resource it can never safely change.

`certManager.clusterIssuer.create` is off by default because a ClusterIssuer is cluster-scoped, and
a chart that owns one cannot be installed twice on the same cluster — which is exactly what the k3d
rehearsal needs to do. `values-staging.yaml` turns it on, because staging is its own cluster with
one release on it.

## Validating a change

```sh
helm lint --strict deploy/charts/event-junkie --values deploy/charts/event-junkie/values-staging.yaml
helm template t deploy/charts/event-junkie --values deploy/charts/event-junkie/values-staging.yaml
deploy/scripts/render-assertions.sh
```

All three are pure functions of the working tree and reach no cluster. CI runs them plus
`kubeconform` in [`validate-chart.yml`](../../../.github/workflows/validate-chart.yml), and `/verify`
runs them on any diff touching `deploy/`. See [`../../AGENTS.md`](../../AGENTS.md) for what is safe
to run and what is not.
