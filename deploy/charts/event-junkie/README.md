# event-junkie Helm chart

Deploys `events-bff`, `events-importer` and `events-frontend` behind one Traefik ingress, with TLS
from cert-manager, and with the importer's admin API and every `/actuator/**` endpoint unreachable
from outside the cluster.

**Status: written, rendered, never installed.** `helm lint`, `helm template`, `kubeconform` and
[`../../scripts/render-assertions.sh`](../../scripts/render-assertions.sh) all pass. None of that is
evidence that a pod starts — the images do not exist yet (#426, #262), and the first actual install
is the k3d rehearsal in #263. Read anything below in that register.

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
`values-k3d.yaml` (#263, written blind and expected to change on first use).

## The parts that are not obvious from the templates

### `/api` is Spring's, not the ingress's

The BFF's controllers are mounted at the root — `/events`, `/venues`, `/artists`, `/promoters`,
`/genres`, `/meta`. The frontend's generated client prepends `/api`, and Vite's dev proxy strips it
locally. So something has to reconcile the two in a cluster.

This chart sets `SPRING_WEBFLUX_BASE_PATH=/api` rather than adding a Traefik `Middleware` doing
`stripPrefix`. The routing rule and the application then agree on the path and no rewrite exists
anywhere. ADR-012's portability argument is "a Docker image plus a Postgres URL"; a rewrite that
lives in one ingress controller's CRD is the first crack in that, whereas `base-path` travels with
the image.

One consequence to confirm rather than assume, on the list for #263: the generated OpenAPI is served
at `/api/v3/api-docs` in a cluster, while `events-frontend`'s `schema.ts` is generated locally from
the un-prefixed application. The paths in the generated client are relative and the client prepends
`/api`, so they should agree.

### Actuator is private because of the port, not because of a rule

`management.server.port: 9001` on both JVM services. The Service publishes both ports, the Ingress
routes only the application one, and `/actuator/**` is therefore *unroutable* rather than merely
unrouted. This is what the BFF's own `application.yaml` asked for. It is set here rather than in
`application.yaml` so local development and the existing tests keep their single-port behaviour.

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

### The BFF crash-loops on a first install, and that is expected

The importer owns the migrations (ADR-005), so on an empty database the BFF starts before the schema
exists. Its readiness probe fails until the importer finishes. No init container and no Helm hook
orders the two: Kubernetes retries, and a startup dependency is a thing that has to stay correct
forever. Ninety seconds of restarts on a first install is documented behaviour, not an incident.

### One replica for the importer is correctness, not cost

Two schedulers means two concurrent imports of the same source. The 60-second tick skips sources
marked `RUNNING`, but that check is a read-then-write with no lock. `values.schema.json` pins
`importer.replicaCount` to `1` so raising it fails at install time; `strategy: Recreate` closes the
same hole during a deploy, when a rolling update would briefly run two pods.
`SELECT … FOR UPDATE SKIP LOCKED` is the prerequisite for relaxing either, and that is an ADR-008
change rather than a values change.

### Read-only root filesystems need writable mounts

`readOnlyRootFilesystem: true` everywhere, which means every path a process writes to has to be an
explicit `emptyDir`. The JVM services get `/tmp` (hsperfdata, and anything a library assumes).
nginx gets `/var/cache/nginx` and `/var/run`, and fails at *startup* without them. If #262's image
relocates either path, these move with it.

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
