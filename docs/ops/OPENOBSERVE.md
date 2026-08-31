# Operating OpenObserve

Logs, metrics and dashboards, on both clusters. **Getting in is [CLUSTER_ACCESS.md](CLUSTER_ACCESS.md) §6b** — port-forward and the root credentials. This
page is what to know once you are in, and what to do when it misbehaves.

**Two instances, not one**, and they share nothing but a bucket. Each holds its own root credential, metadata DB and copy of the rules. So every command
below takes a `--context`, and against the other cluster each one is wrong. Where a value differs, §Two instances has it.

Why OpenObserve at all, and what the alternatives cost, is [ADR-015](../adr/ADR-015_OBSERVABILITY_STACK.md). This page assumes that decision and does not
re-argue it.

## The short version

```sh
kubectl --context event-junkie-staging -n observability \
  port-forward svc/openobserve-openobserve-standalone 5080:5080     # then http://localhost:5080/
flux --context event-junkie-staging get helmrelease openobserve -n flux-system
```

The production forms are the same commands with `--context event-junkie-production`, over that cluster's tunnel (`10.10.0.1`, CLUSTER_ACCESS.md §Two
environments). Both tunnels can be up at once, so the context is the only thing that says which store answered.

- **Cost tracks the number of metric _names_, not the volume.** A thousand idle names is expensive. §The one thing that will bite you.
- **The metadata DB dies with the node.** Dashboards and alerts live in git and are pushed by hand after every rebuild.
- **Changing the Secret restarts nothing**, and both `flux reconcile` and `rollout status` report success anyway.
- **Both clusters, one bucket.** Production writes under a `production/` key prefix. Staging writes at the root. §Two instances.

## The shape of it

|              |                                                                                                                               |
| ------------ | ----------------------------------------------------------------------------------------------------------------------------- |
| Chart        | `openobserve-standalone` **0.92.2**, pinned. A trial whose subject changes under it is not a trial                            |
| Mode         | `ZO_LOCAL_MODE=true` — **single-node, not single-disk**. The flag chooses standalone-vs-cluster; storage is the line below it |
| Storage      | `ZO_LOCAL_MODE_STORAGE=s3` → `event-junkie-o2` in `fsn1`. The corpus is in the bucket                                         |
| Key prefix   | `ZO_S3_BUCKET_PREFIX=production/` on production. **Staging has none** and writes at the root                                  |
| Local disk   | A 5 GB PVC for the write-ahead log, cache and metadata DB — **not** the corpus                                                |
| Retention    | `ZO_COMPACT_DATA_RETENTION_DAYS=14`                                                                                           |
| Ingestion    | The OTel collector gateway, over OTLP. Nothing writes to it directly                                                          |
| Environments | **Both**, since [#880](https://github.com/enorm-labs/event-junkie/issues/880). Separate instances, one shared bucket          |

### Two instances, and where they differ

|                       | Staging                                | Production                                                                       |
| --------------------- | -------------------------------------- | -------------------------------------------------------------------------------- |
| Bucket keys           | the root of `event-junkie-o2`          | `production/` (`ZO_S3_BUCKET_PREFIX`)                                            |
| S3 credential         | the project keypair, all three buckets | its own keypair, rotatable without touching staging                              |
| Alert destination     | `record-only`, into `alert_history`    | the same — no bridge here either                                                 |
| Signal bridge         | deployed, unregistered                 | **not deployed** ([#877](https://github.com/enorm-labs/event-junkie/issues/877)) |
| `ZO_SKIP_SSRF_CHECKS` | set, for the bridge destination        | **not set** — nothing in-cluster to allow yet                                    |
| Database metrics      | `postgres-exporter` → the k3s node     | `postgres-exporter` → the dedicated node, `10.0.1.20`                            |

**Why one bucket rather than two.** The same separation `s3://event-junkie-backups/<environment>/` and the images bucket already make. It keeps one
lifecycle backstop covering both, which is what [LEGAL.md](../LEGAL.md) §7.5 rests on when a compactor stops running.

**Staging is not being given a prefix.** Its file list is sqlite on the PVC. Whether that stores keys absolute or relative to the prefix is not worth
answering against a live store. A wrong guess makes every Parquet already in that list unreadable. The 90-day backstop clears the root of what staging
leaves behind.

**The metadata DB is on that PVC, which means it dies with the node.** Users, dashboards and saved views are metadata. The data is not. A rebuild therefore
comes back with an empty console and a full bucket. That is why the root password can be regenerated freely: the Secret re-seeds it at first boot. It is
also why dashboards live in git rather than in the tool.

## The one thing that will bite you: streams, not rows

**OpenObserve partitions its in-memory table per stream, and a stream is a metric _name_.** Cost tracks the number of distinct names, not the volume of data
under them. A thousand near-idle metric names is expensive. A million samples of one name is cheap.

This is not academic. On 2026-08-21 staging stopped accepting data for three hours:

```
ERROR openobserve_core::metrics::otlp: ingestion error: Error# MemoryTableOverflowError
-> HTTP 503 to the collector, which retries with backoff and then drops
```

**It reads like a memory limit and is not one.** The pod was overflowing at ~700Mi of a 1536Mi limit. Raising the limit bought three minutes. What fixed it was
deleting 362 idle `pg_*` streams that `postgres-exporter` was producing for one metric anybody queries — 60% of all ingestion ([#624](https://github.com/enorm-labs/event-junkie/issues/624)).

**So when ingestion misbehaves, count streams before you touch memory.** The residual is [#625](https://github.com/enorm-labs/event-junkie/issues/625).

## What is filtered, and why each rule exists

Everything is dropped at the **collector gateway**, not at OpenObserve — `deploy/clusters/*/collector.yaml`, processor `filter/drop_infrastructure_noise`.
Both clusters carry the same rules. The files are copies rather than one templated source, so a rule added to one misses the other.
Dropping at the edge means the bytes never cross the network and never occupy a memtable slot.

| Rule                                                                           | Why                                                     | Issue |
| ------------------------------------------------------------------------------ | ------------------------------------------------------- | ----- |
| `^(go\|rest_client\|workqueue)_`                                               | Go runtime and Kubernetes client-library internals      | #615  |
| `apiserver_request_duration_seconds`                                           | The single biggest stream in the system                 | #615  |
| `controller_runtime_reconcile_time_seconds`, `gotk_reconcile_duration_seconds` | Controller histograms nothing queries                   | #615  |
| `^otelcol_.*_batch_send_size$`                                                 | The collector measuring itself, at histogram resolution | #615  |
| `^pg_` except an allowlist                                                     | 362 streams for one queried metric                      | #624  |

**`process_` is deliberately absent**, and that is a correction rather than an oversight. It is a mixed namespace, where the Go Prometheus client and Micrometer both
publish. A prefix drop silently took five of the BFF's and importer's own metrics with it
([#616](https://github.com/enorm-labs/event-junkie/issues/616)).

**Adding a rule:** edit the values, then validate before merging. An OTTL syntax error takes the gateway down, and the gateway is the only thing shipping
anything:

```sh
docker run --rm -v "$PWD:/cfg" otel/opentelemetry-collector-contrib:0.138.0 validate --config=/cfg/minimal.yaml
```

Extract the rules into a minimal config first. The HelmRelease values are not a collector config.

## Dashboards are in git, and pushed by hand

**This is where GitOps stops, and it is a real seam.** OpenObserve dashboards are API objects, not Kubernetes ones, so Flux cannot reconcile them.

```sh
cd deploy/dashboards
./apply.sh                                  # import (or replace) is-it-healthy.json, on staging
./apply.sh --check                          # run every panel query against live data, change nothing
EJ_NODE=ops@10.10.0.1 ./apply.sh            # the same, against production
```

**`EJ_NODE` is what selects the cluster, and it defaults to staging.** Both scripts reach the API over the node's SSH tunnel rather than through an
ingress, because nothing routes OpenObserve. Forget the variable and the command pushes staging's copy to staging again, with no sign that you meant
production.

`is-it-healthy.json` is **generated** by `gen_dashboard.py` — edit the generator, never the JSON. The README there records the PromQL limitations that cost the
most time. `time()` is frozen at the window start, `sort_desc` is unimplemented, and `or vector(0)` does not backfill a missing series.

**`--check` fails on a freshly rebuilt cluster and that is correct** — the panels query data that does not exist yet. Run it once there is a day of history.

**Re-import after any rebuild.** Dashboards are metadata, and metadata is on the PVC.

## Alert rules are in git too, and have the same seam

```sh
cd deploy/alerts
./apply.sh                                  # create or update every rule in alerts.json, on staging
./apply.sh --check                          # evaluate each rule's query against live data, change nothing
./apply.sh --diff                           # compare the cluster's rules to alerts.json
EJ_NODE=ops@10.10.0.1 ./apply.sh            # the same, against production
```

**The rules are one file applied twice, so both clusters run the same nine.** Nothing reconciles them: a rule edited here reaches a cluster when somebody
runs this, and `--diff` is the only thing that reports the gap ([#702](https://github.com/enorm-labs/event-junkie/issues/702)). Run it against both.

`alerts.json` is **generated** by `gen_alerts.py`, exactly like the dashboard. `--check` answers the question the UI cannot: whether a rule's query matches any
series at all. One that matches none never fires, and is indistinguishable from health. It caught a rule summing two counters that was silently
un-fireable whenever either counter was quiet.

**Firings go into the `alert_history` stream, not to a person yet.** Two separate reasons, and only one of them is the phone number:

- [#271](https://github.com/enorm-labs/event-junkie/issues/271) item 4's Signal bridge is unregistered, and
- **OpenObserve's SSRF guard rejects an alert destination inside the cluster**, including
  `signal-cli.observability.svc.cluster.local`. On staging `ZO_SKIP_SSRF_CHECKS` is therefore set, **and paired with an egress NetworkPolicy**
  (`deploy/clusters/staging/observability-netpol.yaml`). That policy lets this pod reach CoreDNS, the public internet on 443 and the Signal bridge. Nothing
  else — not the database, not the Kubernetes API. `deploy/alerts/README.md` has the reasoning. So the remaining blocker on delivery really is just the phone
  number.

**Production sets neither the flag nor the allowance.** It has no bridge to reach. Its rules use the same loopback `record-only` destination, which needs
only `ZO_SSRF_ALLOW_LOOPBACK`. The bridge, the flag and the egress rule arrive together in #877, or not at all.

**Re-apply after any rebuild**, for the same reason as the dashboard: alerts, destinations and templates are all metadata.

## Credentials

The full inventory is [SECRETS.md](SECRETS.md). Two operational traps belong here.

**`openobserve-credentials` exists twice, in `flux-system` and `observability`**, with the same contents. `valuesFrom` resolves in the HelmRelease's namespace.
The chart's `existingRootUserSecret` reads from the release's target namespace instead. Reaching for the wrong copy produces a flat 401 that reads like a wrong password.

**`O2_BASIC_AUTH_HEADER` is derived, and goes stale silently.** The collector authenticates with a header rather than a user and password, and Flux's
`valuesFrom` substitutes a value rather than composing one. Rotate the root password without re-deriving the header, and the collector keeps posting with the old
one while OpenObserve refuses. That looks like an ingestion outage, not a credential problem.

**Changing the Secret restarts nothing.** The S3 keys reach the pod through `envFrom`, which references a Secret by name. The running pod therefore keeps the old value
until something replaces it. `flux reconcile` will report success and `rollout status` will say the rollout is complete, both truthfully, with the old credential
still in place:

```sh
kubectl --context event-junkie-staging -n observability rollout restart statefulset/openobserve-openobserve-standalone
```

## Keeping it up to date

**Nothing does this automatically, and that is a gap rather than a decision.** Dependabot covers gradle, npm, GitHub Actions, OpenTofu and Docker. It has no Helm
ecosystem, so every chart version pinned in `deploy/clusters/*/` is watched by nobody:

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
relies on several of them being what they are. `ZO_LOCAL_MODE` in particular is easy to misread as choosing storage rather than topology.

The pin is not laziness. ADR-015's measurements were taken against 0.92.2, and criterion 2 is a claim about _that_ build.

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

**`--prefix production/files/default/` for the other cluster.** The prefix is also the check that the production instance is configured at all. Objects at
the root, written after it started, mean the setting was dropped.

A healthy system writes into the current hour. Hours that trail off are a backlog. Hours that stop are a refusal.

## What it does not do yet

- **A firing reaches no person.** Nine rules evaluate, and each one posts into the `alert_history` stream instead of to somebody
  ([#271](https://github.com/enorm-labs/event-junkie/issues/271) item 4). So a firing is a row you must go and look at. That is a worse guarantee than it
  sounds, and it is the shape of the eight hours in #813. The Signal route waits only on a registered number now. The SSRF guard that also blocked it is
  gone, traded for the egress policy in `observability-netpol.yaml`.
- **The two instances are copies, not one source.** Nine rules, one dashboard and two collector filter lists exist twice, kept in step by hand. `--diff`
  catches a cluster that drifts from the repository. Nothing catches the two clusters drifting from each other.
