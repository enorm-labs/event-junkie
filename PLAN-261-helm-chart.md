# Plan — #261 Write the Helm chart

> **Working document — delete before this branch merges.** It is committed only so the thinking
> survives a lost session, not because it belongs in the repository. It is not documentation: what
> is worth keeping ends up in `deploy/AGENTS.md`, the chart's `README.md` and
> `docs/PLATFORM_SETUP.md` (§8), and this file goes away in the same PR.

Branch: `feat/261-helm-chart` · Milestone v0.2 · `area:infra`, `size:L`, P0

---

## The steps, in order

**Step 0 is a decision, not work, and it is not mine to take** — everything below waits on it.

| # | Step | § |
|---|---|---|
| **0** | **Agree the sequencing: #427 (rename) first, or chart first.** The branch carries nothing but this plan, which is the only reason this is cheap; it stops being true after step 2 | §9 |
| 1 | **Prove the Helm 3 client renders the chart skeleton** — `Chart.yaml` with `apiVersion: v2` and nothing else. Flux embeds the Helm 3 SDK while the local binary is Helm 4 | §5 |
| 2 | `Chart.yaml`, a skeleton `values.yaml`, and **`_helpers.tpl` first of all** — names, the two label templates, the shared securityContext, the database env block. Everything downstream consumes these, and the labels/selector split is the one mistake that survives to the second release | §3a |
| 3 | ServiceAccounts and the ConfigMap — the objects the Deployments reference | §3a |
| 4 | **`frontend-deployment.yaml` + service** — the simplest workload, no database and no JVM, so it proves the shared scaffolding cheaply | §3.5 |
| 5 | **The render-assertion script, now rather than at the end.** Written against one Deployment it guards every later one; written last it only tells you what you already shipped | §4 |
| 6 | `bff-deployment.yaml` + service — `base-path`, the management port, the R2DBC env | §3.1, §3.2 |
| 7 | `importer-deployment.yaml` + service — `Recreate`, one replica, **and the JDBC Flyway variables** | §3.3, §3.4 |
| 8 | `ingress.yaml` — needs the Services to point at | §3.1 |
| 9 | `clusterissuer.yaml` (optional, off by default) and the `helm test` hook | §3.6, §3.7 |
| 10 | `values-staging.yaml`, `values-k3d.yaml`, `values.schema.json` — once the value surface has settled | §2 |
| 11 | `NOTES.txt` and the chart `README.md` | §2 |
| 12 | **`.github/workflows/validate-chart.yml`** — the gate that keeps it parsing | §4 |
| 13 | **`deploy/AGENTS.md` + `deploy/CLAUDE.md`** | §3b |
| 14 | **The docs sweep** — `verify.prompt.md`, `PLATFORM_SETUP.md`, root `AGENTS.md` and `CLAUDE.md`, and the rest | §8 |
| 15 | Run the gate, then `/open-pr` with `Closes #261`, and move the board to *In review* | §4 |

**Two orderings worth defending, because the obvious order is worse:**

- **Step 1 before anything is written.** Discovering a Helm 3/4 incompatibility after fifteen
  templates exist is a rewrite; after one `Chart.yaml` it is a one-line edit.
- **Step 5 before most of the templates.** Putting checks last is the habit; here the specific
  failure they catch — a `spec.selector` carrying `helm.sh/chart` — does not surface until the
  *second* release of a chart nobody has released once. The assertions are the only thing that
  catches it at all, so they need to exist while the templates are still being written.

**And two things this document has in the wrong place**, fixed by reading them in this order rather
than by moving them: **§9** is chronologically first despite being last on the page, and **§8** is a
deliverable of this PR despite sitting after §6's out-of-scope table.

---

## 0. What I found first, because it changes the shape of the chart

Four things came out of reading the code rather than the issue, and each one moves a decision:

1. **The BFF does not serve under `/api`.** Its controllers are mounted at the root —
   `/events`, `/venues`, `/artists`, `/promoters`, `/genres`, `/meta`. The frontend's client uses
   `baseUrl: '/api'` and Vite's dev proxy strips the prefix (`vite.config.ts:42`). So "route `/api`
   to the BFF" needs a rewrite *somewhere*, and the ingress is not the only candidate.
2. **The importer *does* serve under `/api`** — `/api/admin/**`. So a naive `/api` → BFF ingress
   rule and a naive importer route would collide in the same path space. They don't collide today
   only because the importer gets no ingress at all.
3. **Flyway is JDBC; the apps are R2DBC.** Nothing in `application.yaml` sets a URL — locally
   Spring Boot's Docker Compose support supplies both. In a cluster there is no compose file, so
   the importer needs **two** connection configurations, and the JDBC one is the one that gets
   forgotten. Its failure mode is a migration that never runs, not a startup error.
4. **No image exists for anything.** There is no Dockerfile in the repo and no `bootBuildImage`
   configuration. #262 covers the frontend; **nothing covers the backend images.** See §7.

---

## 1. Scope

Write a single Helm chart that deploys `events-bff`, `events-importer` and `events-frontend` behind
one Traefik ingress, with TLS from cert-manager, and with the importer's admin API and every
`/actuator/**` endpoint unreachable from outside the cluster.

The chart is **written and statically validated** in this issue. It is not installed anywhere —
that is #263 (k3d rehearsal) and #265 (staging). It is not published — that is #264.

Two things ship alongside it: a CI gate that keeps it parsing (§4), and `deploy/AGENTS.md` (§3b).

---

## 2. Layout

```
deploy/
├── AGENTS.md                   # see §3b
├── CLAUDE.md                   # two-line pointer at AGENTS.md, mirroring infra/
└── charts/event-junkie/
    ├── Chart.yaml
    ├── values.yaml             # production-shaped defaults
    ├── values-staging.yaml     # single node, DNS-01, scheduling toggles
    ├── values-k3d.yaml         # for #263: self-signed issuer, no TLS, local images
    ├── values.schema.json      # minimal — required keys and enums only
    ├── README.md               # values table + the two gotchas from §0
    └── templates/
        ├── NOTES.txt
        ├── _helpers.tpl        # names, labels, selectors, shared securityContext, DB env
        ├── bff-deployment.yaml
        ├── bff-service.yaml
        ├── bff-serviceaccount.yaml
        ├── importer-deployment.yaml
        ├── importer-service.yaml
        ├── importer-serviceaccount.yaml
        ├── frontend-deployment.yaml
        ├── frontend-service.yaml
        ├── frontend-serviceaccount.yaml
        ├── configmap.yaml
        ├── ingress.yaml
        ├── clusterissuer.yaml  # optional, off by default
        └── tests/connection-test.yaml
```

Flat `templates/` with the kind in a dashed filename, rather than the `bff/`, `importer/`,
`frontend/` subdirectories I had first — that is the guide's rule (*"template file names should
reflect the resource kind in the name"*, `foo-pod.yaml`, `bar-svc.yaml`, dashed not camelCase, one
resource per file). Subdirectories work, but a reviewer of a Helm chart expects to find
`ingress.yaml` by name, and the prefix already groups them alphabetically.

`deploy/` as the umbrella rather than a top-level `charts/`, because #414 will add
`deploy/clusters/` for Flux and those two belong next to each other. The GHCR path stays
`oci://ghcr.io/enorm-labs/charts/event-junkie` either way — the repo path and the OCI path are
independent.

**One chart, not three.** The three workloads share an ingress, a hostname, a database and a
release lifecycle; subcharts would buy independent versioning nobody wants. Three explicit
Deployment templates sharing `_helpers.tpl` rather than one generic loop over a `components` map:
the frontend has no database and no JVM, the importer has no ingress and a different strategy, so
the "generic" template would be three-quarters conditionals.

---

## 3. The decisions worth arguing about

### 3.1 `/api` → BFF: `spring.webflux.base-path`, not a Traefik middleware

Set `SPRING_WEBFLUX_BASE_PATH=/api` on the BFF and the ingress needs no rewrite at all — the
routing rule and the application agree on the path.

The alternative, a Traefik `Middleware` CRD doing `stripPrefix`, couples the chart to Traefik.
ADR-012's whole portability argument is "a Docker image plus a Postgres URL"; a rewrite that only
exists in one ingress controller's CRD is the first crack in that. `base-path` is a Spring
property that travels with the image.

Consequence to check when it runs: the generated OpenAPI is served at `/api/v3/api-docs` in the
cluster, while the frontend's `schema.ts` is generated locally from the un-prefixed app. The
`paths` in the generated client are relative and the client prepends `/api`, so they agree — but
this is on the list for #263 to confirm rather than assume.

### 3.2 Actuator gets its own port, and that is what keeps it private

`management.server.port: 9001` on both JVM services. The Service then exposes two ports, the
ingress routes only the application port, and `/actuator/**` becomes **unroutable rather than
unrouted**. This is exactly what the BFF's own `application.yaml` comment asks for:

> *"Once the Helm chart exists, prefer moving the management endpoints to their own port
> (`management.server.port`) so the split is enforced by the network rather than by an ingress rule
> that has to stay correct forever."*

Set in the chart's ConfigMap, not in `application.yaml`, so local development and the existing
tests keep their current single-port behaviour. I'll update that comment to point at where the
split now lives.

Probes hit `9001`: `/actuator/health/liveness` and `/actuator/health/readiness`. Spring Boot
enables the probe health groups automatically when it detects Kubernetes, but the chart will set
`management.endpoint.health.probes.enabled=true` explicitly rather than rely on autodetection.

### 3.3 Database credentials: an existing Secret, always

The chart **requires** `database.existingSecret` and never templates a password. No inline
credential path, not even a guarded one — a values key that *can* hold a password is a values key
that eventually does, in a file that gets committed. SOPS lands in #416; until then the Secret is
created out of band, and #263's local values point at one created by `kubectl create secret`.

Both connection styles come from the same Secret, wired in `_helpers.tpl` so neither service can
get half of it:

| Variable | Consumer | Form |
|---|---|---|
| `SPRING_R2DBC_URL` | bff, importer | `r2dbc:postgresql://HOST:5432/DB` |
| `SPRING_R2DBC_USERNAME` / `_PASSWORD` | bff, importer | from Secret |
| `SPRING_FLYWAY_URL` | **importer only** | `jdbc:postgresql://HOST:5432/DB` |
| `SPRING_FLYWAY_USER` / `_PASSWORD` | **importer only** | from Secret |

The host is a value — the private IP that `infra/`'s `postgres_ip` output produces (co-located on
staging, a dedicated node in production). Note that Flyway's key is `SPRING_FLYWAY_USER`, not
`_USERNAME`; getting that wrong fails silently, which is why it is in a table rather than a comment.

### 3.4 The importer: `replicas: 1`, `strategy: Recreate`, and no ingress

Per ADR-008. `Recreate` is not a tuning preference — a rolling deploy briefly runs two schedulers,
and two schedulers means two concurrent imports of the same source. The 60-second tick skips
`RUNNING` sources, but the skip is a read-then-write with no lock; ADR-008 names
`SELECT … FOR UPDATE SKIP LOCKED` as the prerequisite for ever going past one replica.

`values.schema.json` will pin `importer.replicaCount` to `const: 1` so the constraint fails at
install time rather than in a review comment.

Its Service is `ClusterIP` with no Ingress object anywhere in the chart. `kubectl port-forward`
over the WireGuard tunnel is the access path (§8a).

### 3.5 securityContext — §8 items 5, 6, 7 and 12

On every workload: `runAsNonRoot`, `runAsUser: 1000`, `readOnlyRootFilesystem: true`,
`allowPrivilegeEscalation: false`, `capabilities.drop: [ALL]`,
`seccompProfile: RuntimeDefault`, a dedicated ServiceAccount per workload with
`automountServiceAccountToken: false`.

Read-only rootfs needs writable mounts, which is the part that gets missed:

- JVM services — an `emptyDir` on `/tmp`.
- nginx — `emptyDir` on `/var/cache/nginx` and `/var/run`, **and the image must listen on 8080**,
  because a non-root process cannot bind 80. That is a constraint this chart places on #262, and
  I'll comment on that issue rather than discover it during the k3d rehearsal.

Requests *and* limits everywhere — §8 item 12 calls this a security control, not a tuning one, and
on a single node it is. Plus `JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=75`, because a JVM that reads
the container limit and then sizes its heap at 25% of it is the other way this goes wrong.

### 3.6 cert-manager

Ingress annotation `cert-manager.io/cluster-issuer`, value templated. The `ClusterIssuer` itself is
an optional template, **off by default** — it is a cluster-scoped singleton, and a chart that owns
one cannot be installed twice on the same cluster, which is exactly what k3d rehearsal wants to do.

**The chart ships no `crds/` directory.** cert-manager's CRDs belong to cert-manager, and Helm has
no story for upgrading or deleting CRDs once a chart has installed them — the guide's own words are
that there is *"no support at this time for upgrading or deleting CRDs using Helm"*. Owning
somebody else's CRD is how a chart acquires a resource it can never safely change. The guide's
Method 2 — CRDs in one chart, the resources that use them in another — is what we are already
doing by accident: cert-manager is installed separately (#265), and this chart renders only
*instances* (`ClusterIssuer`) of its types.

That does impose an ordering constraint worth writing down: **`helm install` fails outright if
cert-manager is not already present**, because the API server rejects an unknown kind. It is not a
race that resolves itself.

Default to the **Let's Encrypt staging** ACME endpoint in `values.yaml`. Production allows 50
certificates per registered domain per week and 5 duplicates; the default should not be the one
that costs seven days when it is wrong.

Solver is a value: `http01` for production, `dns01` for staging (#265 — no public address for
HTTP-01 to reach). The chart renders both; the Hetzner DNS webhook that DNS-01 needs is #265's
installation, not this chart's dependency.

`event-junkie.com` gets its own `Certificate` for the 301 redirect — a separate ingress rule, not a
wildcard.

### 3.7 Helm test hook

One `helm.sh/hook: test` pod that curls the BFF's readiness endpoint and the frontend's `/` through
their Services. Chart-side only; #414 wires Flux's `test.enable` and the rollback behaviour to it.

---

## 3a. The Helm best-practices guide, applied

Worked through [helm.sh/docs/chart_best_practices](https://helm.sh/docs/chart_best_practices/) —
all eight pages. Most of it is house style and gets adopted silently; these are the ones that
**changed a decision or catch a real bug**, so they belong in the plan rather than only in the
chart:

**Selector labels must be the immutable subset.** Standard labels are
`app.kubernetes.io/name`, `app.kubernetes.io/instance`, `app.kubernetes.io/managed-by`,
`helm.sh/chart` (all REC) plus `version`, `component` and `part-of` (OPT). But a Deployment's
`spec.selector` is **immutable after creation**, and `helm.sh/chart` and
`app.kubernetes.io/version` both change on every release. So `_helpers.tpl` gets two templates —
`event-junkie.labels` (everything) and `event-junkie.selectorLabels` (name + instance only) — and
the selector uses the second. Mixing them produces a chart that installs perfectly and then fails
every subsequent upgrade with an immutable-field error. `component` is what separates bff from
importer from frontend; `part-of: event-junkie` groups them.

Note these are a **different label set from `infra/`'s** (`environment`, `managed-by`, `project`).
Same idea, different namespace, no relationship — worth a line in the chart README so nobody tries
to unify them.

**No floating image tags.** *"A container image should use a fixed tag or the SHA… it should not
use `latest`, `head`, `canary`."* So `image.tag` defaults to `""` and falls back to
`.Chart.AppVersion`, which #264 stamps from the same build that pushes the image — the chart
version and the image tag move together, which is the property §3 of PLATFORM_SETUP wanted from
bundling them as OCI artifacts. `imagePullPolicy: IfNotPresent`.

**No `namespace:` in any template's metadata.** The guide is explicit, and it matters here
specifically: Flux's `HelmRelease` sets `targetNamespace`, and a hardcoded namespace would silently
win over it.

**Values conventions.** camelCase, no hyphens, quote all strings, **every property documented with
a comment that starts with the property's own name**, and maps rather than arrays wherever `--set`
might touch it. The guide prefers flat over nested — I am keeping the per-component nesting
(`bff.*`, `importer.*`, `frontend.*`) under its stated exception, *"a large number of related
variables, and at least one of them is non-optional"*, which is exactly three workloads' worth of
image/resources/replicas.

**ServiceAccounts get the guide's shape**, per component:
`<component>.serviceAccount.create` (default `true`) and `.name`, resolved through a
`event-junkie.<component>.serviceAccountName` helper so a manually-created RBAC binding still
lines up. **No `rbac.create` knob**, and deliberately: no workload here talks to the Kubernetes
API, so there is no Role to create. An empty toggle would imply otherwise.

**Namespaced template names** — `event-junkie.fullname`, never bare `fullname`. Two-space indent,
whitespace inside the braces (`{{ .Values.x }}`, not `{{.Values.x}}`), chomp aggressively.

**No dependencies at all**, and `Chart.yaml` will say so in a comment: PostgreSQL runs on a VM per
ADR-012, so the Bitnami-postgresql-subchart reflex is wrong here, and a subchart would put the
database inside the blast radius of a `helm uninstall`.

**Chart name and version.** `event-junkie` is a valid DNS-1123 label. `version` is SemVer 2 and is
the *chart's*, moving independently of `appVersion`; the `helm.sh/chart` label helper does
`replace "+" "_"` because Kubernetes labels reject `+`.

## 3b. Agent guidance — a dedicated `deploy/AGENTS.md`

**It has to be a new file, and that is settled by the layout rather than by preference.** The
nearest `AGENTS.md` wins, and the chart lives in `deploy/`, not `infra/` — so anything written into
`infra/AGENTS.md` about Helm would never be loaded when an agent is editing a template. The two
directories also have genuinely opposite hazards: `infra/` opens with *"never run `tofu apply` on
your own initiative"* because its commands spend money and change live infrastructure, whereas
`helm lint` and `helm template` are pure functions of the working tree and are safe to run
constantly. Merging them would blunt the one rule that matters in `infra/`.

So: `deploy/AGENTS.md`, plus a two-line `deploy/CLAUDE.md` pointing at it, mirroring `infra/`
exactly (the `CLAUDE.md` is what gets auto-loaded in a subtree; the `AGENTS.md` holds the content).

What goes in it, roughly in the shape `infra/AGENTS.md` uses:

- **Safe commands** — `helm lint`, `helm template`, `kubeconform`, and the render-assertion script.
  And the counterpart to `infra/`'s rule: **never run `helm install`, `upgrade`, `uninstall` or
  `rollback` against a real cluster on your own initiative**, and never against a kubeconfig that
  is not k3d.
- **What state this is in** — "written, rendered, never installed", the same honesty
  `infra/AGENTS.md` applies to `environments/`. `helm template` passing is not evidence that a pod
  starts.
- **Things that will bite** — the selector-label immutability trap; `SPRING_FLYWAY_USER` vs
  `_USERNAME`; the two connection styles for one database; `/api` living in
  `spring.webflux.base-path` and not in the ingress; actuator on 9001 and why an ingress rule must
  never be the thing keeping it private; `readOnlyRootFilesystem` needing `emptyDir` mounts;
  the importer's `replicas: 1` being an ADR-008 correctness constraint, not a cost one.
- **Never put a credential in `values.yaml`**, in any file, guarded or not — the `infra/` rule
  restated for a directory where the temptation looks different.
- **The `event-junkie` naming rule** applies here for the same reason it does in `infra/`: this is
  read next to a domain during an incident.

Cross-references to `docs/PLATFORM_SETUP.md` §§3, 4, 6, 8 and to ADR-005/008/012, in the same style.

**Also needs updating, since the layout gains a directory:** the root `CLAUDE.md`'s multi-module
note (which lists `infra/` as having its own guidance) and the root `AGENTS.md`'s *Key Files*
section.

## 4. Verification

Nothing here can be *run*, so the gate has to be static and it has to be in CI or it will rot the
way §validate-infra was written to prevent:

1. `helm lint --strict deploy/charts/event-junkie` against each values file. `--strict` promotes
   the guide's own conventions — chart name, SemVer, missing icon — from warnings to failures.
2. `helm template` × {default, staging, k3d} piped to
   `kubeconform -strict -summary -schema-location default -schema-location <CRD catalog>` — the
   catalog is needed for `Ingress` annotations and cert-manager's `ClusterIssuer`/`Certificate`.
3. **Assertions on the rendered output**, because lint and schema validation both pass on a chart
   that quietly does the wrong thing. **Written at step 5, not last** — see the step table. A small
   script that greps the rendered manifests for:
   - no Ingress path routes to the importer Service
   - no Ingress path contains `/actuator`
   - `importer` Deployment has `replicas: 1` and `strategy.type: Recreate`
   - every container has `resources.limits.memory` and `securityContext.runAsNonRoot: true`
   - **no `spec.selector` contains `helm.sh/chart` or `app.kubernetes.io/version`** — the
     immutable-field trap from §3a, and the one failure here that would not appear until the
     *second* release
   - no image tag is `latest`, and no template sets `metadata.namespace`
4. New workflow `.github/workflows/validate-chart.yml`, modelled on `validate-infra.yml` — same
   path filter, same "syntax gate, not a correctness gate" framing in the header comment.

**`/verify` is not needed in full.** No Kotlin and no TypeScript changes except the one-line
comment update in the BFF's `application.yaml`, which touches no test. I'll run the chart gate
locally plus a `ktlintCheck` on the touched module to be sure.

**No `--full` re-seed and no snapshot diff.** Nothing in this change touches normalization, the
importers or the data model.

Local tooling is present: `helm v4.2.3`, `kubeconform`, `kubectl`, `yq`. **`k3d` is not installed**
— which is fine, since installing it is #263's first step.

---

## 5. What could go wrong

- **Helm 4 chart apiVersion.** Local Helm is v4. Flux's Helm controller embeds the Helm **3** SDK.
  I will write `apiVersion: v2` and verify the rendered chart installs under a Helm 3 client before
  claiming it works with Flux. Writing a v3-apiVersion chart because the local binary supports it
  would produce a chart Flux cannot install, and #414 is where that would surface. **This is step 1
  precisely because it is cheap there and a rewrite anywhere later.**
- **The BFF starts before the schema exists.** ADR-005 gives Flyway to the importer. On a first
  install the BFF may crash-loop until the importer's migration completes. The readiness probe
  contains it and Kubernetes retries, so I'll let it self-heal rather than add an init container or
  a Helm hook ordering the two — but I'll say so in the chart README, because "the BFF crash-looped
  for ninety seconds on first install" should be a documented expectation, not an incident.
- **`base-path` and springdoc.** §3.1. Confirmed by reading, unproven until #263.
- **The k3d values file is written blind.** It is written here to be *ready* for #263, not to be
  correct — the first person to run it will change it, and that is expected.

---

## 6. Explicitly out of scope

| | Where it belongs |
|---|---|
| NetworkPolicies, PSA namespace labels, SOPS | #416 |
| Trivy image scanning | #416 / #264 |
| Publishing the chart to GHCR | #264 |
| Flux `HelmRelease` / `OCIRepository`, rollback wiring | #414 |
| Installing cert-manager or the Hetzner DNS webhook | #265 |
| ServiceMonitor / metrics scrape config | #415 (the chart leaves a `podAnnotations` hook) |
| Actually installing the chart anywhere | #263 |
| The SEO sidecar in §2.2's diagram | not filed yet — not this issue |

---

## 7. The images, and where they belong

**Nothing owns the backend container images.** #262 containerises the frontend; #264 publishes
images but assumes they can be built; this chart references `image.repository` for all three. There
is no Dockerfile and no `bootBuildImage` configuration anywhere in the repo.

### 7.1 Not in this branch — but next, and before #263

The chart does not need the images to exist: `helm template` and `kubeconform` are pure functions
of the chart, and #264 is already *blocked by* #261 because it publishes the chart and the images
versioned together. Folding two Dockerfiles and a release workflow into a `size:L` chart PR would
make it unreviewable and would not make the chart any more proven.

But the images must land **before #263**, because that is the first step that actually runs
anything, and one detail leaks backwards into this chart today: **`runAsUser` has to match the
UID the image actually runs as.** Paketo's `cnb` user is 1000; a distroless `nonroot` base is
65532. The chart hardcodes one of them.

So: **decide the mechanism now, build it in the next PR.**

### 7.2 Recommendation — a hand-written multi-stage Dockerfile, not `bootBuildImage`

Buildpacks are the lower-effort option and I would normally take them. Two things here point the
other way, and the first is the decisive one:

- **Multi-arch.** #424 has us waiting on Hetzner ARM capacity with an x86 fallback on the table.
  A `linux/amd64,linux/arm64` manifest list makes that decision cost *nothing at the image layer* —
  `docker buildx` does it in one step from a Dockerfile, whereas `bootBuildImage` builds for the
  host architecture only and multi-arch means building twice and assembling a manifest by hand.
  Public repos get free `ubuntu-24.04-arm` runners, so both arches build natively without QEMU.
- **Dependabot already watches four ecosystems.** Adding `docker` makes the base image a tracked
  dependency with a PR when it moves. With buildpacks the base is Paketo's business and invisible
  to the tooling we just finished wiring up.

The cost is losing Paketo's memory calculator — already paid for, since the chart sets
`-XX:MaxRAMPercentage=75` for exactly that reason (§3.5).

Layered jars (`java -Djarmode=tools -jar app.jar extract --layers`) so dependency layers cache
across builds.

### 7.3 Which workflow — a new one, and *not* `build-backend.yml`

`build-backend.yml` and `build-frontend.yml` run on **every pull request**. Adding a push to GHCR
there would publish an image from every PR, including from a fork. The split:

| | Where | Trigger |
|---|---|---|
| **Build** the image, don't push | extend the existing build workflows | PR + push to `main` |
| **Push** image + chart to GHCR | new release workflow — **#264** | release / tag |

Building without pushing on a PR is the cheap half and worth doing in the image issue itself: a
Dockerfile that stopped building is exactly the rot `validate-infra.yml` was written to catch.

### 7.4 GHCR — what actually has to happen

`enorm-labs/event-checker` is a **public repo in an organisation**, which makes most of this free
and unauthenticated. Confirmed against the container-registry docs:

- **No credential to create for CI.** `permissions: packages: write` plus
  `docker/login-action` with `${{ secrets.GITHUB_TOKEN }}`. The token gets `admin` on packages
  published by that repo's workflow. This is the "already authenticated" claim in PLATFORM_SETUP §3,
  and it holds.
- **A local `helm push` or `docker push` needs a classic PAT** with `write:packages`. GitHub
  Packages does **not** support fine-grained tokens — reaching for one is the obvious wrong turn
  and it fails with a confusing error.
- **`LABEL org.opencontainers.image.source=https://github.com/enorm-labs/event-junkie`** in every
  Dockerfile. It links the package to the repo, which is what puts it on the repo page and makes it
  inherit the repo's access. Worth setting `revision` and `version` alongside it from the same
  `gitCommit` provider `build.gradle.kts` already computes for `build-info.properties`.
  **Note the URL — `event-junkie`, not `event-checker`**, which is only correct if #427 has landed.
  It is one of the few places the rename is not cosmetic: a label pointing at the pre-rename URL
  still resolves through GitHub's redirect, but the package-to-repository link is matched on the
  canonical name, so the package would silently fail to attach. §9 is why #427 goes first.
- **Packages are private on first publish — always.** This is the one that will bite: the first
  deploy gets `ImagePullBackOff` and nothing in the logs says "visibility". Flipping each package
  to public is a **click in package settings**, once per package — three images plus the chart, so
  four. It is the same kind of "clicked, not declared" carve-out as the Object Storage bucket in
  §Phase A, and belongs written down next to it rather than rediscovered.
- **Public images pull anonymously**, so the cluster needs **no `imagePullSecret`** — which is why
  the chart's `imagePullSecrets` value defaults to empty. It stays in the chart for k3d and for the
  case where a package is still private.
- **Storage and bandwidth are free for public packages**, so untagged versions accumulating is
  clutter rather than cost. Not worth a cleanup workflow now; worth a line in the issue.
- Naming: **`ghcr.io/enorm-labs/event-junkie/{bff,importer,frontend}`**, parallel to the chart's
  `oci://ghcr.io/enorm-labs/charts/event-junkie` from §3. `event-junkie` rather than
  `event-checker` for the same BRANDING reason `infra/` uses it — this is read next to a domain, not
  next to a module. All lowercase, which both names already are.

### 7.5 Filed as #426

*"Containerise the backend services and build the images in CI"* — `area:infra`, `area:ci`,
`size:M`, v0.2, Ready / P1. It carries the two Dockerfiles, multi-arch buildx, the OCI labels,
`docker` added to Dependabot, build-on-PR in the existing workflows, and the GHCR visibility step as
a documented manual carve-out. #262 stays separate but should land in the same stretch, since #263
needs all three images at once.

---

## 8. Docs to update — do not leave this to the end

When `infra/` landed, the "which docs mention this directory?" sweep was a separate pass after the
fact. `deploy/` gets the same sweep, and it is part of the PR rather than a follow-up. Every file
below currently references `infra/`; each is a candidate for the parallel `deploy/` reference, and
the ones marked **must** are not optional:

| File | What changes |
|---|---|
| **`.github/prompts/verify.prompt.md`** | **must** — `/verify` already grows a branch when the diff touches `infra/` (`tofu fmt`/`validate`, ShellCheck). It needs the same for `deploy/`: `helm lint --strict`, `helm template`, `kubeconform`, the render assertions. Without this the chart is outside the pre-PR gate |
| **`docs/PLATFORM_SETUP.md`** | **must** — §10 Phase C step 11 goes from planned to written (in the same *"written, never applied"* register Phase B uses); §6 gains the ClusterIssuer/solver split as built; §9 gains what the chart's static gate does and does not prove |
| **`CLAUDE.md`** (root) | **must** — the multi-module note names `infra/` as carrying its own guidance; `deploy/` joins it |
| **`AGENTS.md`** (root) | **must** — *Key Files* gains the chart; *Build & Dev Commands* gains the four safe Helm commands; *CI/CD & Automation* gains `validate-chart.yml` |
| `README.md` | the repository-layout list |
| `docs/DEVELOPMENT.md` | the local commands, next to where `infra/`'s sit |
| `docs/adr/ADR-012_CLOUD_PLATFORM.md` | it says *"TLS certificates — cert-manager or Traefik ACME, which the Helm chart (#261) must provision"*. Point that at the chart now that it exists |
| `.pre-commit-config.yaml` | optional — a local `helm lint` hook mirroring `tofu-fmt`. No `check-yaml` hook exists, which is lucky: Helm templates are not valid YAML and a generic parser hook would reject every one of them. Worth a comment so nobody adds one |
| `.github/dependabot.yml` | **nothing to do** — Dependabot has no Helm-chart ecosystem, and the chart has no subchart dependencies to track. Recording the non-change so it is not re-investigated |

**No new ADR.** ADR-012 already decided Helm, k3s and cert-manager; §3's decisions (`base-path`,
the management port, an existing-Secret-only credential path) are implementation choices inside
that decision, and their home is the chart README and `deploy/AGENTS.md`. Saying so explicitly
because "should this be an ADR?" is otherwise the question that gets asked in review.

**One interaction to watch:** #427 renames every `event-checker` reference to `event-junkie` and
rewrites BRANDING.md's naming rule — see §9, which concludes it should go first.

---

## 9. Sequencing — #427 before this, not after

**Recorded, not acted on.** Nothing here changes until it is agreed.

The order that came out of it is **#427 → #261 → #426 → #263**, and the reason it is worth
disturbing an in-progress issue for is that `feat/261-helm-chart` **carries no chart yet** — one
docs-only commit parking this file, which rebases over anything without conflict. There is nothing
to stash, so the usual objection to renaming mid-stream does not apply — and it stops being true at
step 2, the moment a template exists.

Three things point the same way:

- **The chart's documentation gets written once.** §8 has this PR editing `README.md`, root
  `AGENTS.md`, root `CLAUDE.md`, `docs/DEVELOPMENT.md`, `PLATFORM_SETUP.md` and
  `verify.prompt.md`. #427's sweep touches most of the same files. Whichever lands second takes the
  conflicts, and writing doc prose that a mechanical sweep immediately rewrites is the worse half
  of that trade.
- **`deploy/AGENTS.md` gets simpler, not just earlier.** Under today's BRANDING rule it needs a
  paragraph explaining why `deploy/` uses the public name when the repository does not — the one
  `infra/AGENTS.md` already carries. After #427 there is no exception to explain, so the paragraph
  is never written rather than written and then deleted.
- **#427 must precede #426 regardless**, because that is where something first gets published to
  GHCR under a name that then cannot be changed cheaply (§7.4). Moving it one place further forward
  costs nothing.

**Two things that are not mine to do**, and both should be settled before #427 starts:

1. **Renaming the GitHub repository is outward-facing** and needs an explicit go-ahead on that
   specific step. Everything else in #427 — the 51-file sweep, the BRANDING.md rewrite, the
   `ALTER DATABASE` — is ordinary work on a branch.
2. **The local directory is a separate rename.** `gh repo rename` does not touch
   `/Users/NORMLAN/repos/event-checker`. Renaming that too changes Claude Code's project path,
   which orphans this project's memory directory
   (`~/.claude/projects/-Users-NORMLAN-repos-event-checker/`) and any absolute path in
   `.claude/settings.local.json`. Cheap to handle deliberately, annoying to discover afterwards.

**If the answer turns out to be "chart first" instead**, the cost is bounded and known: one
paragraph in `deploy/AGENTS.md` written and later deleted, plus a rebase of #427 over this PR's doc
edits. That is a real option, not a strawman — it just costs more than doing it the other way.
