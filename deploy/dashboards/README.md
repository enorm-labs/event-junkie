# OpenObserve dashboards

`is-it-healthy.json` is [#271](https://github.com/enorm-labs/event-junkie/issues/271)'s
_"a dashboard that answers 'is it healthy' in one screen"_.

```sh
./apply.sh            # import it, replacing any dashboard with the same title
./apply.sh --check    # run every panel's query against live data, change nothing
```

Then, because OpenObserve is deliberately not routed through the ingress:

```sh
kubectl --context event-junkie-staging -n observability \
  port-forward svc/openobserve-openobserve-standalone 5080:5080
# http://localhost:5080/web/dashboards
```

## The one thing to know before editing

**Edit `gen_dashboard.py`, not `is-it-healthy.json`.** The JSON is generated. The schema repeats
about forty lines of boilerplate per panel, and a typo in one copy of it is invisible — the panel
renders blank rather than erroring.

```sh
python3 gen_dashboard.py > is-it-healthy.json && ./apply.sh && ./apply.sh --check
```

## This is where GitOps stops, and that is not an oversight

Dashboards are **OpenObserve API objects, not Kubernetes ones**, so Flux cannot reconcile them.
Nothing in the cluster will notice if someone edits a panel in the UI and forgets to bring it back
here, and nothing will restore this file's version after a rebuild unless somebody runs `apply.sh`.

That is a real gap and it is accepted for now rather than solved, because the alternative — a Job
that POSTs on every reconcile — has to answer "what happens to changes made in the UI", and the
honest answer while the dashboard is still changing weekly is "silently destroy them". Revisit when
it stops changing. **The rebuild checklist in `docs/ops/CLUSTER_BOOTSTRAP.md` is the thing that has
to remember `apply.sh`**, since that is when the gap actually bites.

## What the panels say, and why those

The top row is the answer; the two rows under it are why.

| Panel                  | Question it answers                                            |
| ---------------------- | -------------------------------------------------------------- |
| Oldest source          | Is the importer completing its cycle at all?                   |
| Sources stale > 12h    | How much of the catalogue is going stale?                      |
| Future events          | Can the site show anything? **The failure no HTTP check sees** |
| Node memory available  | Is the node about to fall over?                                |
| Twenty stalest sources | One broken scraper, or all of them?                            |
| Events in the database | Is the trend up or down?                                       |
| PostgreSQL size        | Disk filling — one of #271's five required alerts              |
| Node load and memory   | Is a stall CPU or memory?                                      |
| Certificate expiry     | Expiry — another of the five, and the silent one               |

**"Future events" is the panel that justifies the whole exercise.** A venue redesigns its site, the
scraper keeps returning 200 and writing nothing, the importer reports success, and the listings
empty out over a fortnight while every infrastructure check stays green. `ImporterMetrics`' KDoc
makes the same argument; this is where you would see it.

**"Node memory available" was added after the fact.** On 2026-08-20 the node global-OOMed — load
99 on two cores, `openobserve` killed by the kernel, the API server flapping — and nothing was
watching. A dashboard about the application that cannot show the node dying under it is only half
a dashboard.

## OpenObserve's PromQL is partial, in ways that fail silently

All four of these were found by running queries against the live instance. **None of them produce
an error you would notice** — you get a blank panel or a plausible wrong number.

**1. `time()` is frozen at the query window's start.** Not the evaluation step, as in Prometheus.
Measured over a six-hour window it returned the same value at every step:

```
first point  [1787219100, '1787219100']   <- window start
last point   [1787240700, '1787219100']   <- still the window start
```

So `time() - some_epoch_gauge` under-reports age by the whole window width, and goes **negative**
for anything newer than the window start. The first version of the staleness panel read
`-2.07 hours`. Use `timestamp(x) - x`, which is per-step and correct.

**This is not only a dashboard problem.** ADR-015's criterion 1 — _"source X has imported 0 events
for 3 consecutive runs"_ — is a staleness rule, so **every alert of that shape has to avoid
`time()` too.**

**2. `sort` and `sort_desc` are not implemented.** `This feature is not implemented: Unsupported
Function: SortDesc`. Use `topk`/`bottomk`, which are.

**3. `or vector(0)` does not reliably backfill an empty result.** The idiom that does is
`sum(expr > bool N)` — the `bool` modifier yields 0/1 for every series, so the sum is defined even
when nothing crosses the threshold. **`count(expr > bool N)` is a trap**: it counts every series
regardless, so it returns 84 when the answer is 0.

**4. `absent()` returns 1 for a metric that plainly exists.** Not used here; noted so nobody builds
an alert on it.

## Every query aggregates the pod labels away, and must

Each metric arrives carrying about **29 OTel resource labels**, including `k8s_pod_name`,
`k8s_pod_uid`, `k8s_replicaset_uid`, `service_instance_id` and `container_image_tag`. So:

- **a pod restart starts a brand-new series**, and
- **so does every deploy**, because the image tag is a snapshot version
  (`0.1.1-snapshot.20260820145738.g12be9a8`) and it is a label.

A bare `db_events` returned **six series in one hour** for what is logically two, and an _instant_
query at `now` returned **nothing at all** — the newest series had not been written within the
lookback. Every query here starts with `max by (<real label>)` to collapse them.

**This costs storage as well as correctness, though less than it first looked.** I initially blamed
label churn for the stream count going 603 -> 982 in a day. That was wrong: streams count metric
_names_, and `pg_*` alone is **362 of them**, added by postgres-exporter in #608. Label churn
multiplies series _within_ a stream, which is a different and smaller bill.

Where the rows actually are, measured: `apiserver_*` is **51% of all stored rows**, and
`apiserver_request_duration_seconds_bucket` alone is 277,368 rows and 203 MB. Dropping metric
families nothing queries is the big lever; this is the correctness one.

The churn itself is fixed in #611 — a `resource` processor on the gateway's metrics pipeline, which
deletes `container.image.tag` and eleven other identity attributes while keeping `k8s.pod.name`. The
`max by (...)` in every query here stays regardless: it is what makes a panel correct across a pod
restart, which is legitimate churn that no processor should remove.

## What is missing, and why

**Six of the nine business meters have no data yet**, so no panel is written against them:
`importer_run_outcome_total`, `importer_events_written_total`, `importer_scrape_failures_total`,
`importer_run_duration_seconds`, `importer_source_field_coverage` and `bff_events_served_total`.

The cause is already recorded in ADR-015: **a Micrometer counter that has never fired is absent
from `/actuator/prometheus`.** They appear once each event happens for the first time on staging.
Add the panels then — writing them now would ship nine working panels and six blank ones, which
teaches everyone to ignore blank panels.

`data_quality{source,metric}` is the same story and belongs with
[#386](https://github.com/enorm-labs/event-junkie/issues/386) rather than here — that issue is the
data-quality dashboard, and #271 says to co-design rather than build two.
