# Vendored dashboards

Three files here derive from [openobserve/dashboards](https://github.com/openobserve/dashboards),
Apache-2.0, © OpenObserve Inc., vendored at commit
`6e2fc4d11b844f45a25a7cfd0cf445b58424158d` (2026-08-11):

| File                         | Upstream                                                                    |
| ---------------------------- | --------------------------------------------------------------------------- |
| `openobserve-internals.json` | `OpenObserve/OpenObserve Internals.dashboard.json`                          |
| `kubernetes-events.json`     | `Kubernetes(openobserve-collector)/Kubernetes _ Events.dashboard.json`      |
| `kubernetes-namespaces.json` | `Kubernetes(openobserve-collector)/Kubernetes  _ Namespaces.dashboard.json` |

**They are not copies, and `adapt_upstream.py` is the difference.** Upstream targets a distributed
installation on a schema two versions old. That script holds every change and why each one is
needed, so a refresh is a re-run rather than an exercise in remembering what was edited last time:

```bash
git clone --depth 1 https://github.com/openobserve/dashboards.git /tmp/o2dash
python3 deploy/dashboards/adapt_upstream.py /tmp/o2dash
cd deploy/dashboards && ./apply.sh --check     # every panel must return data
```

Then record the new commit above. **Re-read the adaptations while you are there** — each is
conditional on something that can change: our schema version, the metrics this build exports, and
the fact that this is a one-pod deployment.

## What was not taken, and why

Upstream ships eleven dashboards across those two directories. Eight are deliberately absent, and
the reasoning is here so it is not rediscovered as an oversight. Every number below was measured
against production.

### `OpenObserve Infrastructure` — a topology we do not have

Eight copies of the same seven pod-resource panels, one per deployment role, each filtered
`k8s_pod_name=~".*querier.*"` and its siblings. This cluster runs `ZO_LOCAL_MODE=true`: **one pod,
`role="all"`**, no querier, ingester, compactor, alertmanager, router or etcd. Both k3s
single-server and OpenObserve local mode keep their metadata in SQLite, so the etcd tab can never
have data at all. Its base metric `k8s_pod_cpu_utilization` is not one the collector emits here
either — we have `k8s_pod_cpu_limit_utilization` and `_request_utilization`.

Roughly **62 of its 69 panels would be permanently blank**, and only its Postgres tab would work,
where `p_pg` in `is-it-healthy.json` already covers that ground.

### The other Kubernetes dashboards ([#974](https://github.com/enorm-labs/event-junkie/issues/974))

| Dashboard                          | Queries returning data | Why it is not here                                                                                                                                                                               |
| ---------------------------------- | ---------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `Kubernetes _ Nodes`               | 10 / 10                | Works, and duplicates `is-it-healthy.json`'s node load, memory utilisation and memory available                                                                                                  |
| `Kubernetes _ Namespace (Pods)`    | 9 / 11                 | The same metrics as `kubernetes-namespaces.json` at finer grain — a filter away in the one we took                                                                                               |
| `Kubernetes _ Namespace (Pod)`     | 9 / 11                 | As above                                                                                                                                                                                         |
| `Kubernetes _ Node (Pods)`         | 8 / 10                 | As above                                                                                                                                                                                         |
| `Kubernetes Nodes Pressure`        | 0 / 3                  | Needs `k8sclusterreceiver` — see below                                                                                                                                                           |
| `Kubernetes _ Namespace (Objects)` | 0 / 4                  | Needs `k8sclusterreceiver` — see below                                                                                                                                                           |
| `kubernetes_overview`              | 46 SQL panels          | Needs the `k8s_pod_phase` and `_o2_service_graph` streams, neither of which exists here. `kube_pod_status_phase` is the near-equivalent, so this is a rewrite of 46 panels rather than an import |

**Five of them work today**, which is worth stating plainly because #974 was filed on the opposite
assumption. They are absent because they answer questions the two we took already answer, not
because they are broken. Take one later if it earns its place; the cost of a dashboard nobody opens
is not zero.

### `k8sclusterreceiver` was considered and declined

Enabling it would light up seven queries across two dashboards. It also duplicates facts already
ingested: kube-state-metrics exports **89 `kube_*` metrics** here, including
`kube_daemonset_status_number_ready`, `kube_deployment_spec_replicas` and
`kube_node_status_condition` — which is `Nodes Pressure`'s content under a different name.

[`README.md`](README.md) measures `apiserver_*` at **51% of all stored rows**, and
[#611](https://github.com/enorm-labs/event-junkie/issues/611) is about label churn on this same
node, which has global-OOMed once. Adding a second source of facts we already have, to fill two
dashboards we would otherwise not import, is the wrong trade on this hardware.

**If node pressure is wanted, one panel against `kube_node_status_condition` in `is-it-healthy.json`
is the cheap version** — no new ingest, and a separate issue rather than this one.
