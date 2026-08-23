# Operating OpenObserve

Logs, metrics and dashboards for staging. **Getting in is [CLUSTER_ACCESS.md](CLUSTER_ACCESS.md) §6b** — port-forward and the root credentials. This page is
what to know once you are in, and what to do when it misbehaves.

Why OpenObserve at all, and what the alternatives cost, is [ADR-015](../adr/ADR-015_OBSERVABILITY_STACK.md). This page assumes that decision and does not
re-argue it.

## The shape of it

|              |                                                                                                                               |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| Chart        | `openobserve-standalone` **0.92.2**, pinned. A trial whose subject changes under it is not a trial                            |
| Mode         | `ZO_LOCAL_MODE=true` — **single-node, not single-disk**. The flag chooses standalone-vs-cluster; storage is the line below it |
| Storage      | `ZO_LOCAL_MODE_STORAGE=s3` → `event-junkie-o2` in `fsn1`. The corpus is in the bucket                                         |
| Local disk   | A 5 GB PVC for the write-ahead log, cache and metadata DB — **not** the corpus                                                |
| Retention    | `ZO_COMPACT_DATA_RETENTION_DAYS=14`                                                                                           |
| Ingestion    | The OTel collector gateway, over OTLP. Nothing writes to it directly                                                          |
| Environments | **Staging only.** Production runs no observability stack yet                                                                  |

**The metadata DB is on that PVC, which means it dies with the node.** Users, dashboards and saved views are metadata; the data is not. A rebuild therefore
comes back with an empty console and a full bucket — which is why the root password can be regenerated freely during a rebuild (it is re-seeded from the
Secret at first boot) and why dashboards live in git rather than in the tool.

## The one thing that will bite you: streams, not rows

**OpenObserve partitions its in-memory table per stream, and a stream is a metric _name_.** Cost tracks the number of distinct names, not the volume of data
under them. A thousand near-idle metric names is expensive; a million samples of one name is cheap.

This is not academic. On 2026-08-21 staging stopped accepting data for three hours:

```
ERROR openobserve_core::metrics::otlp: ingestion error: Error# MemoryTableOverflowError
-> HTTP 503 to the collector, which retries with backoff and then drops
```

**It reads like a memory limit and is not one.** The pod was overflowing at ~700Mi of a 1536Mi limit. Raising the limit bought three minutes. What fixed it was
deleting 362 idle `pg_*` streams that `postgres-exporter` was producing for one metric anybody queries — 60% of all ingestion ([#624](https://github.com/enorm-labs/event-junkie/issues/624)).

**So when ingestion misbehaves, count streams before you touch memory.** The residual is [#625](https://github.com/enorm-labs/event-junkie/issues/625).

## What is filtered, and why each rule exists

Everything is dropped at the **collector gateway**, not at OpenObserve — `deploy/clusters/staging/collector.yaml`, processor `filter/drop_infrastructure_noise`.
Dropping at the edge means the bytes never cross the network and never occupy a memtable slot.

| Rule                                                                           | Why                                                     | Issue |
| ------------------------------------------------------------------------------ | ------------------------------------------------------- | ----- |
| `^(go\|rest_client\|workqueue)_`                                               | Go runtime and Kubernetes client-library internals      | #615  |
| `apiserver_request_duration_seconds`                                           | The single biggest stream in the system                 | #615  |
| `controller_runtime_reconcile_time_seconds`, `gotk_reconcile_duration_seconds` | Controller histograms nothing queries                   | #615  |
| `^otelcol_.*_batch_send_size$`                                                 | The collector measuring itself, at histogram resolution | #615  |
| `^pg_` except an allowlist                                                     | 362 streams for one queried metric                      | #624  |

**`process_` is deliberately absent**, and that is a correction rather than an oversight. It is a mixed namespace: the Go Prometheus client and Micrometer both
publish under it, so a prefix drop silently took five of the BFF's and importer's own metrics with it ([#616](https://github.com/enorm-labs/event-junkie/issues/616)).

**Adding a rule:** edit the values, then validate before merging — an OTTL syntax error takes the gateway down, and the gateway is the only thing shipping
anything:

```sh
docker run --rm -v "$PWD:/cfg" otel/opentelemetry-collector-contrib:0.138.0 validate --config=/cfg/minimal.yaml
```

Extract the rules into a minimal config first; the HelmRelease values are not a collector config.

## Dashboards are in git, and pushed by hand

**This is where GitOps stops, and it is a real seam.** OpenObserve dashboards are API objects, not Kubernetes ones, so Flux cannot reconcile them.

```sh
cd deploy/dashboards
./apply.sh              # import (or replace) is-it-healthy.json
./apply.sh --check      # run every panel query against live data, change nothing
```

`is-it-healthy.json` is **generated** by `gen_dashboard.py` — edit the generator, never the JSON. The README there records the PromQL limitations that cost the
most time: `time()` is frozen at the window start, `sort_desc` is unimplemented, and `or vector(0)` does not backfill a missing series.

**`--check` fails on a freshly rebuilt cluster and that is correct** — the panels query data that does not exist yet. Run it once there is a day of history.

**Re-import after any rebuild.** Dashboards are metadata, and metadata is on the PVC.

## Alert rules are in git too, and have the same seam

```sh
cd deploy/alerts
./apply.sh              # create or update every rule in alerts.json
./apply.sh --check      # evaluate each rule's query against live data, change nothing
```

`alerts.json` is **generated** by `gen_alerts.py`, exactly like the dashboard. `--check` answers the question the UI cannot: whether a rule's query matches any
series at all. One that matches none never fires and is indistinguishable from health — it caught a rule that summed two counters, which was silently
un-fireable whenever either counter was quiet.

**Firings go into the `alert_history` stream, not to a person yet.** Two separate reasons, and only one of them is the phone number:

- [#271](https://github.com/enorm-labs/event-junkie/issues/271) item 4's Signal bridge is unregistered, and
- **OpenObserve used to refuse any alert destination inside the cluster** — its SSRF guard rejected
  `signal-cli.observability.svc.cluster.local`, which is item 4's architecture blocked by a control unrelated to registration. Settled on 2026-08-23:
  `ZO_SKIP_SSRF_CHECKS` is set **and paired with an egress NetworkPolicy** (`deploy/clusters/staging/openobserve-netpol.yaml`) that lets this pod reach CoreDNS,
  the public internet on 443 and the Signal bridge — and nothing else, including the database and the Kubernetes API. `deploy/alerts/README.md` has the
  reasoning. So the remaining blocker on delivery really is just the phone number.

**Re-apply after any rebuild**, for the same reason as the dashboard: alerts, destinations and templates are all metadata.

## Credentials

The full inventory is [SECRETS.md](SECRETS.md); two operational traps belong here.

**`openobserve-credentials` exists twice, in `flux-system` and `observability`**, with the same contents. `valuesFrom` resolves in the HelmRelease's namespace;
the chart's `existingRootUserSecret` reads from the release's target namespace. Reaching for the wrong copy produces a flat 401 that reads like a wrong password.

**`O2_BASIC_AUTH_HEADER` is derived, and goes stale silently.** The collector authenticates with a header rather than a user and password, and Flux's
`valuesFrom` substitutes a value rather than composing one. Rotate the root password without re-deriving the header and the collector keeps posting with the old
one while OpenObserve refuses — which looks like an ingestion outage, not a credential problem.

**Changing the Secret restarts nothing.** The S3 keys reach the pod through `envFrom`, which references a Secret by name, so the running pod keeps the old value
until something replaces it. `flux reconcile` will report success and `rollout status` will say the rollout is complete, both truthfully, with the old credential
still in place:

```sh
kubectl --context event-junkie-staging -n observability rollout restart statefulset/openobserve-openobserve-standalone
```

## Keeping it up to date

**Nothing does this automatically, and that is a gap rather than a decision.** Dependabot covers gradle, npm, GitHub Actions, OpenTofu and Docker — it has no
Helm ecosystem, so every chart version pinned in `deploy/clusters/*/` is watched by nobody:

| Component                                    | Pinned at      |
| -------------------------------------------- | -------------- |
| `openobserve-standalone`                     | 0.92.2         |
| `openobserve-collector`                      | 0.4.6          |
| `opentelemetry-operator`                     | 0.121.0        |
| `cert-manager`                               | v1.21.1        |
| `cert-manager-webhook-hetzner`               | 0.8.0          |
| `signal-cli-rest-api` (image, digest-pinned) | 0.100-rootless |

Until that is automated, upgrading is deliberate:

```sh
helm repo add openobserve https://charts.openobserve.ai && helm repo update
helm search repo openobserve/openobserve-standalone --versions | head -5
```

Then bump the `version:` in the HelmRelease, open a PR, and watch the reconcile. **Read the chart's changelog for `ZO_*` defaults**, because this deployment
relies on several of them being what they are — `ZO_LOCAL_MODE` in particular has already been misread once, as choosing storage rather than topology.

The pin is not laziness: ADR-015's measurements were taken against 0.92.2, and criterion 2 is a claim about _that_ build.

## When it misbehaves

Read in this order. Each step rules something out that the next one would otherwise waste time on.

```sh
flux --context event-junkie-staging get helmrelease openobserve -n flux-system
kubectl --context event-junkie-staging -n observability logs openobserve-openobserve-standalone-0 --since=5m | grep -E 'ERROR|WARN'
kubectl --context event-junkie-staging -n observability logs o2c-openobserve-collector-gateway-collector-0 --since=5m | grep -c 503
kubectl --context event-junkie-staging top pod -n observability
```

| Symptom                               | Almost always                                                                                                                                          |
| ------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------ |
| Release failed, never started         | `openobserve-credentials` missing or malformed. Intended shape — a missing credential should stop the deploy, not produce a server nobody can log into |
| `MemoryTableOverflowError`, 503s      | Stream count. **Not** the memory limit — see above                                                                                                     |
| Collector 401s, OpenObserve fine      | `O2_BASIC_AUTH_HEADER` not re-derived after a password change                                                                                          |
| Console empty after a rebuild         | Expected. Metadata is on the PVC; re-import the dashboards                                                                                             |
| Panels empty, no errors               | Either no data yet, or the query. `apply.sh --check` distinguishes them                                                                                |
| Ingestion stopped, no errors anywhere | Check the bucket. Objects written per data-hour tell you whether it is behind or refusing                                                              |

Bucket-side check, which is the one that settles "is it actually storing anything":

```sh
cd infra && direnv exec . sh -c \
  'aws s3api list-objects-v2 --bucket event-junkie-o2 --prefix files/default/ --query "Contents[].Key" --output text' \
  | tr '\t' '\n' | grep -oE '2026/[0-9]{2}/[0-9]{2}/[0-9]{2}' | sort | uniq -c | tail -6
```

A healthy system writes into the current hour. Hours that trail off are a backlog; hours that stop are a refusal.

## What it does not do yet

- **No alerting.** The rules and the Signal route are [#271](https://github.com/enorm-labs/event-junkie/issues/271) items 3 and 4, blocked on the eSIM. Today
  the dashboard is something a human looks at, which is a worse guarantee than it appears.
- **Nothing on production.** Standing it up there needs the memory budget re-checked against a node that also runs the application — and #625 answered first.
- **Nothing watches shedding.** `otelcol_exporter_send_failed_metric_points` is collected and unused; a dropped metric currently looks exactly like a quiet
  period, which is the same blindness [#618](https://github.com/enorm-labs/event-junkie/issues/618) records for importers.
