# ADR-018: Probe semantics — readiness includes the database, liveness never does

## Status

**Accepted (2026-08-18) — the BFF's readiness group is `readinessState, r2dbc, eventsSchema`, and its liveness group
is `livenessState` and nothing else.**

**Implemented in [#438](https://github.com/enorm-labs/event-junkie/issues/438)**, which exists because
[#263](https://github.com/enorm-labs/event-junkie/issues/263) measured the failure while installing the chart on k3d for the first time.

**Verified on k3d 2026-08-18**, installing this chart against an empty database on the same path #263 used. The BFF
reported Ready at `19:57:16`, about four seconds _after_ Flyway completed its migration at `19:57:12.012`. #263
measured Ready 1.2 seconds _before_ the schema existed. Zero restarts, `helm install --wait` returned normally, and
the full rehearsal passed through to the chain and `helm test`. On the running pod, `r2dbc` reported its validation
query as `validate(REMOTE)`. That confirms in a cluster what the source says: it runs no query, and would have been
`UP` for the whole of the original window.

Supersedes nothing. It constrains what `event-junkie.jvmProbes` in the Helm chart may point at, and it is why the
importer's `application.yaml` sets no readiness group at all.

## Context

### The measurement

[ADR-005](ADR-005_MIGRATIONS_OWNED_BY_IMPORTER.md) gives the importer the Flyway migrations. On a first install the BFF
therefore starts against a database whose schema does not exist yet. The chart deliberately orders neither pod, with
no init container and no Helm hook. The argument is that a startup dependency has to stay correct forever, while
Kubernetes already retries. That argument still holds. What was wrong was the assumption that the BFF would visibly
fail while it waited.

Installed on k3d against an empty PostgreSQL:

| Event                                | Time           |
| ------------------------------------ | -------------- |
| BFF pod reports **Ready**            | `07:55:59`     |
| Flyway creates the schema (importer) | `07:56:00.451` |

The BFF was Ready **about 1.2 seconds before the schema it queries existed**, with zero restarts.
`/actuator/health/readiness` returned `{"status":"UP"}`, because the readiness group contained only `readinessState`.
That is Spring's own signal that the application context finished starting. For that window the Service had a healthy
endpoint, and every `/api/events` request would have failed.

**That is worse than crash-looping**, which is what the chart README claimed happened. A crash-looping pod receives no
traffic. A Ready one does.

1.2 seconds is also the _best_ case: one migration, a local PostgreSQL, a warm page cache. A cold database on another
host with more migrations widens it. None of that changes the BFF's readiness, because it is not looking.

It is not only a first-install problem. **If the database becomes unreachable at any point the BFF keeps reporting Ready**, because nothing in the readiness
group watches the connection.

### The obvious fix is not sufficient on its own

Spring Boot auto-configures a health contributor for the R2DBC `ConnectionFactory`. It is registered as **`r2dbc`**,
not `db`, which is the JDBC one. The bean is `r2dbcHealthContributor`, and Spring strips the suffix. Adding it to the
readiness group catches an unreachable database.

It does not catch what was measured. `ConnectionFactoryHealthIndicator` calls `Connection.validate(REMOTE)` and runs no
query, and no `management.health.r2dbc.validation-query` property exists to point it at one. During the 1.2-second
window PostgreSQL was up and the connection was valid. Only the schema was missing. `r2dbc` would have reported `UP`
throughout, and the table above would still be true.

Closing the measured window needs a second contributor that reads something real.

### The argument against putting a dependency in readiness, and why it does not apply here

The standard advice is that readiness should report on the pod, not on the world. A readiness probe that fails when a
shared dependency fails takes every replica out of the load balancer at once. That converts a partial failure into a
total one. The advice rests on two assumptions:

1. **the replica can still serve something** without the dependency, and
2. **the dependency is per-replica**, so its failure is partial to begin with.

Neither holds. [ADR-012](ADR-012_CLOUD_PLATFORM.md) puts every replica on one node against one PostgreSQL, so a
database failure is already total. There is no partial failure left to preserve. The BFF is also a read-through API
with no cache, because [#269](https://github.com/enorm-labs/event-junkie/issues/269) is unbuilt. With the database
gone it can serve `/meta` and `/actuator/**`, and nothing a visitor asked for.

Including the database therefore changes not _whether_ the site is down, but _how the failure presents_. Traefik
answers `503` with no healthy backend, instead of every pod answering `200`-framed query errors. The `503` is the
honest signal. It is retriable, and it is what a status page and an alert rule key on. A CDN and a browser already
know how to treat it as transient.

The real cost is flapping. A brief database hiccup now drains and refills the endpoint list, instead of failing a
handful of requests. That is what the readiness probe's `failureThreshold` is for. The chart's existing
`periodSeconds: 10, failureThreshold: 3` already means **~30 seconds of sustained failure** before a pod leaves the
Service. Those numbers were previously arbitrary. They are now load-bearing.

## Decision

**1. The BFF's readiness group is `readinessState, r2dbc, eventsSchema`.**

`include` replaces a group's default membership rather than adding to it, so `readinessState` is repeated explicitly.
`eventsSchema` is `EventsSchemaHealthIndicator`, which runs `SELECT EXISTS (SELECT 1 FROM <schema>.event)`. That query
was chosen for three reasons. It returns exactly one row even against an empty table, and a first install is a
legitimate state that must not report `UNKNOWN`. PostgreSQL stops it at the first tuple, so it stays O(1) as the table
grows. And it exercises the real grant on the real table, rather than asking a catalog whether one is visible.

The two contributors are kept **separate rather than folded into one**. The endpoint then distinguishes "the database
is gone" from "the schema is not migrated yet", and those have different operators and different fixes.

**2. The BFF's liveness group is `livenessState`, and must stay that way.**

Liveness answers "is this process wedged", not "is the system healthy". A database-dependent liveness probe restarts
every replica during a database outage. PostgreSQL then returns to a fleet mid-cold-start: a recoverable outage made
slower by the thing meant to protect it.

There is a second, sharper reason specific to this chart. **The `startupProbe` points at the liveness path.** A
liveness group that included the database would give a first install `failureThreshold: 30 × periodSeconds: 5`, or 150
seconds, to wait for the importer's migrations. Kubernetes would then kill the container. That restores exactly the
crash-loop the README used to describe, this time for real.

**3. The probe timings in `event-junkie.jvmProbes` do not change.** `readinessProbe.failureThreshold: 3` at `periodSeconds: 10` is the blip tolerance this
decision depends on. Lowering either is a semantic change, not tuning.

**4. `management.endpoint.health.show-details` is `always`.** Without it the group aggregates to a bare
`{"status":"DOWN"}`, and splitting `r2dbc` from `eventsSchema` buys nothing. This is safe for the same reason
`/actuator/prometheus` is. The chart moves the whole management port off the application port, and no Ingress routes
it. So `/actuator/**` is _unroutable_ rather than merely unrouted (`docs/ops/PLATFORM_SETUP.md` §7).

**5. And the importer: readiness stays at Spring's `readinessState` default, deliberately.**

The same question asked of the importer gets the opposite answer, because the premise differs at every point:

- **Its readiness gates no traffic.** The importer's Service is `ClusterIP` with no Ingress backend anywhere in the
  chart. Nothing routes to it, so removing it from its own endpoint list changes nothing anyone can observe.
- **It runs one replica under `strategy: Recreate`** ([ADR-008](ADR-008_IMPORT_JOB_SCHEDULING.md)), so readiness affects only whether `kubectl rollout status`
  returns.
- **The admin API is the tool an operator reaches for during a database incident.** Making it unready in that moment removes the diagnostic while the incident
  is running.
- **Its database dependency is already enforced at the strongest available point.** Flyway runs at startup. If the
  database is unreachable the context fails and the pod restarts. There is no equivalent of the BFF's window to close.

The decision is a comment in the importer's `application.yaml` rather than a property, so the absence reads as an
answer rather than an oversight. Setting `readiness.include: readinessState` explicitly would state the same thing,
and add a value that `src/test/resources/application.yaml` must shadow correctly forever, for no behavioural gain.

## Consequences

### Positive

- **A Ready BFF can serve.** The window between "pod Ready" and "schema exists" closes. A database that disappears
  later takes the pods out of rotation, instead of leaving them advertising a service they cannot provide.
- **The failure is legible.** `/actuator/health/readiness` names which of the two dependencies is down, on a port nothing outside the cluster can reach.
- **A wrong contributor name fails loudly.** `management.endpoint.health.validate-group-membership` defaults to
  `true`, so a group naming something that does not exist fails the context at startup. It is left at its default for
  that reason: a silently smaller readiness group is the failure this ADR is about.

### Negative

- **A database blip now costs a drain-and-refill cycle** of the Service's endpoints, bounded at ~30 seconds of sustained failure by `failureThreshold: 3`.
  Previously those requests failed individually and the endpoints never moved.
- **`helm install --wait` now genuinely couples the two workloads.** The BFF reaches Ready only after the importer's
  migrations finish. `scripts/k3d-rehearsal.sh` allows `--timeout 5m` against a measured 1.2-second gap. The staging
  `HelmRelease` sets `timeout: 3m`, and that is now a shared budget rather than two independent ones.
- **Two extra connections per pod per 10 seconds**, one per contributor. Negligible against a pool sized 5–12, but it is new load that probes did not previously
  place on the database.

### Revisit when

**[#269](https://github.com/enorm-labs/event-junkie/issues/269) lands.** Caching in the BFF is the change that makes
assumption (1) above start to hold. Once a meaningful fraction of requests can be answered without the database, a pod
without one is no longer useless. Draining it then stops being obviously right. Re-argue this decision at that point,
rather than inheriting it.

### Not applied to

- **The frontend.** nginx serving static assets has no database and no equivalent question. Its probes stay a `GET /`
  on the application port.

## References

- `events-bff/src/main/kotlin/de/norm/events/EventsSchemaHealthIndicator.kt` — the `eventsSchema` contributor and why its query is shaped the way it is
- `events-bff/src/main/resources/application.yaml` — the groups themselves
- `deploy/charts/event-junkie/templates/_helpers.tpl` (`event-junkie.jvmProbes`) — the probes that read them
- `deploy/charts/event-junkie/README.md` — the measurement, in the chart's own words
- [ADR-005](ADR-005_MIGRATIONS_OWNED_BY_IMPORTER.md) (why the BFF starts against a schema it does not own) · [ADR-008](ADR-008_IMPORT_JOB_SCHEDULING.md) ·
  [ADR-012](ADR-012_CLOUD_PLATFORM.md)
- [Spring Boot — Kubernetes probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health.kubernetes-probes)
