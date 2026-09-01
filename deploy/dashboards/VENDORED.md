# Vendored dashboard

`openobserve-internals.json` is derived from
[openobserve/dashboards](https://github.com/openobserve/dashboards), Apache-2.0, © OpenObserve Inc.

Vendored at commit `6e2fc4d11b844f45a25a7cfd0cf445b58424158d` (2026-08-11), from
`OpenObserve/OpenObserve Internals.dashboard.json`.

**It is not a copy, and `adapt_upstream.py` is the difference.** Upstream targets a distributed
installation on a schema two versions old. That script holds every change and why each one is
needed, so a refresh is a re-run rather than an exercise in remembering what was edited last time:

```bash
git clone --depth 1 https://github.com/openobserve/dashboards.git /tmp/o2dash
python3 deploy/dashboards/adapt_upstream.py \
  "/tmp/o2dash/OpenObserve/OpenObserve Internals.dashboard.json" \
  > deploy/dashboards/openobserve-internals.json
python3 deploy/dashboards/lint_dashboard.py deploy/dashboards/openobserve-internals.json
cd deploy/dashboards && ./apply.sh --check     # every panel must return data
```

Then record the new commit above. **Re-read the adaptations while you are there** — each is
conditional on something that can change: our schema version, the metrics this build exports, and
the fact that this is a one-pod deployment.

## The other file in that directory is deliberately not here

`OpenObserve Infrastructure.dashboard.json` is eight copies of the same seven pod-resource panels,
one per deployment role, each filtered `k8s_pod_name=~".*querier.*"` and its siblings. This cluster
runs `ZO_LOCAL_MODE=true`: **one pod, `role="all"`**, no querier, ingester, compactor, alertmanager,
router or etcd. Both k3s single-server and OpenObserve local mode keep their metadata in SQLite, so
the etcd tab can never have data at all. Its base metric `k8s_pod_cpu_utilization` is not one the
collector emits here either — we have `k8s_pod_cpu_limit_utilization` and `_request_utilization`.

Roughly **62 of its 69 panels would be permanently blank**, and only its Postgres tab would work,
where `p_pg` in `is-it-healthy.json` already covers that ground. See the argument in
[`README.md`](README.md) about what blank panels teach people.

The `Kubernetes(openobserve-collector)` dashboard from the same repository is a separate decision —
it needs the `k8sclusterreceiver`, which this cluster does not run. That is
[#974](https://github.com/enorm-labs/event-junkie/issues/974).
