# OpenObserve dashboards

Four, and each answers a question the others do not.

| File                         | Question                                                                                        | Origin                                                                                                                         |
| ---------------------------- | ----------------------------------------------------------------------------------------------- | ------------------------------------------------------------------------------------------------------------------------------ |
| `is-it-healthy.json`         | **Is anything wrong?** One screen, mostly this project's own signals                            | ours — [#271](https://github.com/enorm-labs/event-junkie/issues/271)                                                           |
| `openobserve-internals.json` | **Why, when the answer is OpenObserve itself?** WAL, compaction, storage, ingestion, cache, API | [upstream](https://github.com/openobserve/dashboards), adapted — [#971](https://github.com/enorm-labs/event-junkie/issues/971) |
| `kubernetes-events.json`     | **What is Kubernetes complaining about?** OOMKills, evictions, failed probes, scheduling        | upstream, adapted — [#974](https://github.com/enorm-labs/event-junkie/issues/974)                                              |
| `kubernetes-namespaces.json` | **Which namespace is using the node?** CPU, memory and network per namespace                    | upstream, adapted — [#974](https://github.com/enorm-labs/event-junkie/issues/974)                                              |

**`kubernetes-events.json` reads a stream nothing else here does.** `k8s_events` has been collected all along — 2,777 rows in a day on production — and
until now nothing read it. A probe failing repeatedly appears there as `Unhealthy`, and nowhere else.

```sh
./apply.sh                          # import every *.json here, replacing by title
./apply.sh --check                  # run every panel's query against live data, change nothing
./apply.sh --diff                   # compare the cluster's copies to these files, change nothing
./apply.sh is-it-healthy.json       # just one of them, for iterating
EJ_NODE=ops@10.10.0.1 ./apply.sh    # any of the above, against production

python3 lint_dashboard.py is-it-healthy.json    # can OpenObserve draw this? offline, no cluster
python3 test_lint_dashboard.py                  # the linter's own checks
```

**No argument means every dashboard in this directory, deliberately.** With more than one file, the
one that is not named is the one that drifts — which is the failure `--diff` exists to catch, given
somewhere to hide.

**`lint_dashboard.py` runs on every `apply.sh` invocation, before anything touches the network** —
there is no way to reach a cluster without it, including `--check` and `--diff`.

**`EJ_NODE` selects the cluster and defaults to staging.** Both environments run an OpenObserve since
[#880](https://github.com/enorm-labs/event-junkie/issues/880), and these are files applied to each — nothing reconciles them, so the two can differ.
Forget the variable on a production run and the command succeeds, reports what it pushed, and writes to staging again. Each run prints the cluster it
resolved before it does anything.

**`--check` validates this file, `--diff` validates the deployment, and `lint_dashboard.py` validates that OpenObserve can draw either.** The first two read
alike and the third answers a question neither asks — see below. The first `--diff` run found the cluster serving **9 panels against this file's 12** — "Sources that have never succeeded" ([#618](https://github.com/enorm-labs/event-junkie/issues/618)) and both OpenObserve ingest panels
([#625](https://github.com/enorm-labs/event-junkie/issues/625)) were in git and had never been imported. `--check` was green throughout, because the panels it
checks are the ones in the file. See [#702](https://github.com/enorm-labs/event-junkie/issues/702) and `../alerts/README.md`, where the same gap cost 17 false
alert firings.

Then, because OpenObserve is deliberately not routed through the ingress:

```sh
kubectl --context event-junkie-staging -n observability \
  port-forward svc/openobserve-openobserve-standalone 5080:5080
# http://localhost:5080/web/dashboards
```

## Neither JSON file is edited by hand

**`is-it-healthy.json` comes from `gen_dashboard.py`. The other three come from `adapt_upstream.py`** run over an upstream clone — see [`VENDORED.md`](VENDORED.md) for the commit they
were taken at, the command that refreshes them, and which upstream dashboards were deliberately left
behind. They are a transformation rather than a generator because the content is upstream's; writing the changes down is what keeps the file
re-pullable instead of turning every refresh into archaeology.

What the adaptation does, and why each part is needed, is in that script's docstring. The short
version: upstream is schema v5 on a 48-column grid, filters on `pod=~".*querier.*"` for a
distributed deployment we do not run, sources one variable from a stream we do not have, and
includes panels whose metrics this build never exports.

**Edit `gen_dashboard.py`, not `is-it-healthy.json`.** The JSON is generated. The schema repeats
about forty lines of boilerplate per panel, and a typo in one copy of it is invisible — the panel
renders blank rather than erroring.

```sh
python3 gen_dashboard.py > is-it-healthy.json && ./apply.sh && ./apply.sh --check
```

## Two schema facts OpenObserve does not document, and both fail silently

Neither is in the documentation, neither raises anything, and between them they cost six panels
([#969](https://github.com/enorm-labs/event-junkie/issues/969)). Both are derived from the 69
dashboards in [openobserve/dashboards](https://github.com/openobserve/dashboards), which is the only
corpus of known-good OpenObserve JSON there is.

**1. The single-value panel type is `metric`. `stat` is Grafana's name for it.** OpenObserve accepts
`stat`, stores it, returns it, and draws nothing. Across those 69 dashboards — over 1,100 panels —
`stat` appears **zero** times and `metric` appears **201**. Five panels here were `stat` from the
day they were written; every one of their queries returned data the whole time.

The corpus is suggestive; the UI is proof. The instance serves its own bundle, and it carries the
enumeration:

```sh
curl -s http://localhost:5080/web/assets/index-*.js | grep -oE '\["area","line",[^]]*\]'
["area","line","bar","scatter","area-stacked","donut","pie","h-bar","stacked","h-stacked",
 "heatmap","metric","gauge","geomap","maps","table","sankey","custom_chart","html","markdown"]
```

Twenty types, no `stat`. That list is what `lint_dashboard.py` checks against, and **re-reading it
after an OpenObserve upgrade is the maintenance this file asks for** — it is the only authority, since
the documentation does not enumerate them.

**2. The v8 grid is 192 columns wide, not 48.** It widened between schema v5 and v7: every reference
v3/v5 dashboard ends at exactly `x + w == 48`, every v7/v8 one at exactly `192`, and row heights
roughly doubled with it (median 9 -> 18). A 48-column layout in a v8 dashboard is _valid_ — it
renders, in the left **quarter** of the screen.

The second is the reason `lint_dashboard.py` checks that the widest panel **reaches** the right edge
rather than merely fitting inside it. Fitting catches nothing: 48 fits in 192, and so does the 174
that production drifted to after someone resized panels in the UI.

**3. There is no logarithmic axis, and asking for one is silent.** The chart builder hard-codes
`yAxis:{type:"value"}` and exposes no key for the type. ECharts' log scale is bundled — `t.type="log"`,
`logBase` — so the code is present and unreachable. `y_axis_min` and `y_axis_max` _are_ real keys and
do work. This matters whenever one series is orders of magnitude larger than the rest, which is
[#972](https://github.com/enorm-labs/event-junkie/issues/972): the answer there was `clamp_max` in the
query, because the axis could not be asked to help.

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
| Sources stale > 36h    | How much of the catalogue is going stale?                      |
| Future events          | Can the site show anything? **The failure no HTTP check sees** |
| Node memory available  | Is the node about to fall over?                                |
| Twenty stalest sources | One broken scraper, or all of them?                            |
| Events in the database | Is the trend up or down?                                       |
| PostgreSQL size        | Disk filling — one of #271's five required alerts              |
| Node load and memory   | Is a stall CPU or memory?                                      |
| Certificate expiry     | Expiry — another of the five, and the silent one               |
| Metrics dropped        | **Is anything above true?** Shedding on the way in (#625)      |
| OpenObserve memtable   | How close the ingest path is to rejecting writes               |

**"Future events" is the panel that justifies the whole exercise.** A venue redesigns its site, the
scraper keeps returning 200 and writing nothing, the importer reports success, and the listings
empty out over a fortnight while every infrastructure check stays green. `ImporterMetrics`' KDoc
makes the same argument; this is where you would see it.

**The last two panels are about the other nine.** Every panel above shows a gap the same way
whether nothing happened or nothing was recorded, and on 2026-08-23 roughly **half of all scraped
metric points were being dropped** before they reached storage — 4.44M rejected by OpenObserve and
8.45M never queued by the collector, in 48 hours. A dashboard that cannot distinguish "quiet" from
"blind" is the thing #625 fixed; these two are how it stays fixed.

**Both of `p_shedding`'s series have to exist on a healthy collector, or the panel cannot do that
job.** Its second half was `otelcol_exporter_enqueue_failed_metric_points_total`, which a collector
creates only once something has already failed to enqueue — so the half meant to certify calm was
blank whenever things were calm, and read as a broken panel. It is now
`otelcol_receiver_refused_metric_points_total`, which is present from start-up and carries the same
signal: queue pressure surfaces at the receiver once the exporter stops accepting.
[`../alerts/README.md`](../alerts/README.md) records `ej-ingest-shedding` reaching this conclusion
first, for the same metric and the same reason — **one query per always-present series**.

**`apply.sh --check` reports 14/14 on production.** It has not always: `p_memtable` was blank until
the release carrying `ZO_PROMETHEUS_ENABLED: "true"` reconciled — the chart ships that setting off,
which is why the outage had to be diagnosed from log lines — and `p_shedding`'s second query was on
a series a healthy collector never creates. Both are resolved, so **a `NO DATA` here is now a
finding rather than a known gap**, which is the state a check is worth having in.

**"Node memory available" was added after the fact.** On 2026-08-20 the node global-OOMed — load
99 on two cores, `openobserve` killed by the kernel, the API server flapping — and nothing was
watching. A dashboard about the application that cannot show the node dying under it is only half
a dashboard.

## The blind spot this dashboard used to have, and how it was closed

**A source that had NEVER succeeded was absent from the staleness panels entirely.**
`importer_source_last_success` springs into existence on a source's first success, so a venue that
failed every time it was tried had no series — and something with no series cannot be stale, late or
failing. It is simply not there.

As of 2026-08-20 that was **86 sources and 84 series**, and the two missing were the only two that
were actually broken:

```
quasimodo   FAILED  retries=2  last_import 08-17 11:50  last_success NULL  HTTP 500
club-ost    FAILED  retries=1  last_import 08-19 11:50  last_success NULL  HTTP 500
```

So "0 sources stale" did not mean "0 sources broken", and for a while the panel simply said so in its
description — a label on the gap rather than a fix for it.

**`importer_source_has_succeeded` is the fix (#618)**, and "Sources that have never succeeded" is the
panel that reads it. The gauge is published for **every** enabled row by `MetricsRefreshService`, from
the first refresh after start-up, so it exists whether or not the source has ever worked. A per-run
counter could not do this: Micrometer counters live in the process, vanish on restart, and do not
reappear until the next run — which on a 24h interval is most of any given day.

**Two facts, kept separate on purpose.** _Never worked_ and _worked and went stale_ need different
responses — fix the scraper versus wait for the retry — so they are two panels reading two series
rather than one number covering both. This is the same shape ADR-015 records for counters, applied to
a gauge.

**The thresholds assume a 24h cycle**, because that is what `import_interval_minutes = 1440` is. The
first version of this dashboard used 12h, which against a 24h interval flags all 84 venues every
single day — a panel that is red as a matter of routine is a panel everyone learns to ignore. 36h is
"missed a whole cycle and half of the next".

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
