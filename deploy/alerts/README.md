# Alert rules

The rules [#271](https://github.com/enorm-labs/event-junkie/issues/271) requires, as OpenObserve v2 alert objects.

```sh
./apply.sh            # create or update every rule
./apply.sh --check    # evaluate each rule's query against live data, change nothing
```

**Edit `gen_alerts.py`, not `alerts.json`.** The JSON is generated, `apply.sh` regenerates it before doing anything, and the same argument the dashboards make
applies here: forty lines of scaffolding per rule, and a typo in one copy is invisible.

## What `--check` answers that the UI does not

Three states, and they are different questions:

|              |                                                                                          |
| ------------ | ---------------------------------------------------------------------------------------- |
| `NO DATA`    | the query matches no series — **the rule can never fire**, and looks exactly like health |
| `WOULD FIRE` | the query crosses its threshold right now                                                |
| `ok`         | returns data, below the threshold                                                        |

`WOULD FIRE` is not necessarily wrong. On staging today `ej-importer-stale` is one, because `loge` genuinely has not succeeded in 70 hours.

**This check earned its place immediately.** The first version of `ej-ingest-shedding` was one rule summing two counters:

```promql
sum(rate(otelcol_exporter_send_failed_metric_points_total[5m]))
  + sum(rate(otelcol_exporter_enqueue_failed_metric_points_total[5m]))
```

`--check` returned `NO DATA` for it while both halves were healthy. A collector only exports `enqueue_failed` once something has failed to enqueue, so after a
restart the series does not exist — and **a binary operation with an empty side yields an empty result, not the other side's value**. The rule was therefore
un-fireable during exactly the normal operation it was meant to watch, and nothing in the UI would have said so. It is now two rules, each on a series that is
always present.

## The rules

| Rule                        | Fires when                                                                                                                                   | #271 item          |
| --------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------- | ------------------ |
| `ej-site-down`              | an application Deployment has zero available replicas                                                                                        | site down          |
| `ej-importer-stale`         | the stalest source passes 36h against a 24h interval                                                                                         | importer failing   |
| `ej-source-never-succeeded` | a source has never once completed a run ([#618](https://github.com/enorm-labs/event-junkie/issues/618))                                      | importer failing   |
| `ej-catalogue-emptying`     | future events fall below 500, from a normal ~3,000                                                                                           | zero events        |
| `ej-source-emptied`         | one source holds zero future events after holding more than twenty this week ([#700](https://github.com/enorm-labs/event-junkie/issues/700)) | zero events        |
| `ej-node-disk-filling`      | less than 15% of the node's filesystem is free                                                                                               | disk filling       |
| `ej-certificate-expiry`     | the soonest certificate is inside 14 days                                                                                                    | certificate expiry |
| `ej-ingest-shedding`        | OpenObserve is rejecting writes ([#625](https://github.com/enorm-labs/event-junkie/issues/625))                                              | —                  |
| `ej-ingest-queue-saturated` | the collector's export queue is over 80% full                                                                                                | —                  |

**The zero-events failure is two rules, and keeping both is deliberate.** ADR-015's criterion 1 is per-source — a venue whose scraper still returns 200 while
writing nothing — and `ej-source-emptied` is that rule at last, on the `importer_source_events_future` gauge
[#700](https://github.com/enorm-labs/event-junkie/issues/700) added. It is refreshed from the database rather than accumulated in the process, the way
[#618](https://github.com/enorm-labs/event-junkie/issues/618) did for `has_succeeded`, because the obvious signal — `importer_events_written_total` — is a
Micrometer counter that resets on every deploy and is absent until it first increments, against a 24h import interval.

`ej-catalogue-emptying` stays alongside it rather than being replaced, because the two see different failures: a per-source rule cannot see the importer being
down, the scheduler stopping, or a database restored empty, since those empty every source at once and none of them individually looks different from the rest.

**Zero is not the same as broken, and that is the whole difficulty of the per-source rule.** A venue with nothing on for three weeks — summer break, a
refurbishment — sits at zero legitimately with a perfectly healthy scraper. So the rule asks for zero _now_ against a non-zero recent history for the **same**
series:

```promql
sum((max by (source) (importer_source_events_future) == bool 0)
  * (max by (source) (max_over_time(importer_source_events_future[7d])) > bool 20))
```

Both halves come from one metric, so the vector match is one-to-one on `source` and a gap in some _other_ metric cannot make it misfire — the failure
`has_succeeded` exists to avoid. **The 7-day lookback fails closed**: an ingest gap shortens the history side and the rule goes quiet rather than crying wolf,
which is the right direction given #625 dropped roughly half of all metric points for days.

**The same lookback makes the rule blind for its first week, and that is worth knowing before trusting it.** The history side has nothing to read until
`importer_source_events_future` has been ingested for a while, so a source already sitting at zero when the metric ships has no `> 20` past to be contrasted
against and will not be named. Only a source that collapses _after_ the deploy fires it. That is inherent to telling _broken_ from _legitimately empty_ — the
distinction is entirely historical, so a rule with no history cannot make it — and it is a reason to check the per-source numbers by hand once, rather than a
reason to widen the rule. `ej-source-never-succeeded` covers the other end: a source that has never worked at all needs no history.

**36h, not 24h**, for the reason [#617](https://github.com/enorm-labs/event-junkie/issues/617) gives about the dashboard panel: the interval _is_ 24h, so a 24h
threshold flags the whole catalogue every day as a matter of routine, and a rule that fires every day is a rule that gets muted.

## Where the notifications go, and why not to Signal yet

Every rule routes to `record-only`, a destination that POSTs the firing back into OpenObserve as a row in the `alert_history` stream. Firing is therefore
observable now, which is what makes these rules exercised rather than hypothetical while item 4 waits on the eSIM.

**Signal is blocked by something other than the missing number, and this is the finding worth carrying to #271.** OpenObserve refuses any alert destination whose
URL resolves inside the cluster:

```
signal-cli.observability.svc.cluster.local  ->  400 Destination URL blocked by SSRF guard
openobserve-…svc.cluster.local              ->  400 Destination URL blocked by SSRF guard
```

An OpenObserve webhook into `signal-cli-rest-api` **is** item 4's architecture. Two flags govern it (`config.rs:1177`), and they are not equivalent:

|                          |                                                                             |
| ------------------------ | --------------------------------------------------------------------------- |
| `ZO_SSRF_ALLOW_LOOPBACK` | the process may notify **itself** and nothing else. What `record-only` uses |
| `ZO_SKIP_SSRF_CHECKS`    | removes the check for **every** destination. Set, **with the policy below** |

**The decision, taken 2026-08-23: the guard comes off and the containment moves to the network.** A URL allowlist inside a process is advisory — it constrains
the feature, not the process — while an egress policy constrains anything the pod can be made to do. `deploy/clusters/staging/openobserve-netpol.yaml` is that
policy, and the two shipped in the same change:

```
OpenObserve -> CoreDNS:53                resolving anything at all
OpenObserve -> the public internet:443   Hetzner Object Storage, where the data lives
OpenObserve -> signal-cli:8080           the alert route, once item 4 has a number
```

PostgreSQL on the private network, the Kubernetes API, the kubelet and every other pod are unreachable from this pod, so a destination aimed at them fails at
the network rather than at a check somebody can turn off.

**What it does not fix, stated rather than glossed:** a destination may still point at any _public_ address, so whoever holds the root credential can exfiltrate
alert bodies. That is inherent to having a webhook feature, it is not what the SSRF guard addressed, and that credential already reads every metric and log in
the system.

**The namespace still has no default-deny.** This policy is egress-only and selects one pod, which is enough to bound the feature being unblocked and is not the
same thing as hardening the namespace — that needs an allowance per conversation for the operator, both collectors, the exporter and the bridge, and a k3d
rehearsal to prove none of them break. That is [#662](https://github.com/enorm-labs/event-junkie/issues/662).

A destination is mandatory, incidentally: `POST /api/v2/{org}/alerts` with `destinations: []` returns `Alert destination or workflows is required`, with or
without `creates_incident`. So "rules now, delivery later" needs _a_ destination, which is why the loopback one exists.

## This is where GitOps stops, again

Alerts are OpenObserve API objects, not Kubernetes ones, so Flux cannot reconcile them — the same seam `../dashboards/` sits on, with the same consequences:
nothing notices if somebody edits a rule in the UI, and nothing restores these after a node rebuild unless `apply.sh` is run.
[`CLUSTER_BOOTSTRAP.md`](../../docs/ops/CLUSTER_BOOTSTRAP.md) is what has to remember, and it now lists both.

## `ej-site-down` watches availability, not scrape health

The first version was `sum(up == bool 0) > 0` — any scrape target down. **It fired on the first deploy after it went live**, and would have fired on every one
after that: a rolling update leaves the replaced pod's target failing for about five minutes while it ages out of service discovery. The alert was correct and
useless, which is the combination that gets a rule muted.

Neither `avg_over_time(up[10m]) == 0` nor `min_over_time` fixes it. When a target _disappears_ rather than reporting failure, the only samples inside the window
are the zeros, so a "down for the whole window" test still passes.

`kube_deployment_status_replicas_available{deployment=~"event-junkie.*"} == bool 0` is the deploy-stable form: a rolling update keeps at least one replica
available by definition, and zero available replicas is what "the site is down" actually means. Checked against the 13:07 deploy on 2026-08-23 — 61 samples
across the hour, every one of them zero-unavailable, while `up` went to 0 twice.

## Six traps these rules are written around

All five were established by running things against the live instance, and every one of them fails **silently** — a rule that looks configured and does nothing.

1. **`time()` is frozen at the query window's start.** An age is `timestamp(x) - x`. The `time()` form under-reports by the whole window width and goes negative
   for anything newer than the window start.
2. **`count(expr == bool 0)` counts every series, always.** Measured: `count(up == bool 0)` returns 18 — the number of scrape targets — while
   `sum(up == bool 0)` returns 0, the number that are down. A site-down rule written with `count` fires permanently.
3. **`frequency` is in minutes, whatever the documentation says.** The OpenAPI schema annotates it `(seconds)` and the source carries a TODO claiming alerts
   are in seconds. Measured on this deployment: an alert created with `frequency: 1` evaluated at 11:32:00, 11:33:10, 11:34:20, 11:35:30 — a 70-second cadence,
   one minute plus scheduler slack. The first version of these rules used seconds, so `ej-site-down` asked for 60 and got **one evaluation an hour**, and
   `ej-certificate-expiry` asked for 3600 and would next have run in **two and a half months**. Nothing errored; the rules simply sat there.
4. **A `<` or `<=` condition fires when it matches zero rows, and delivers nothing.** `handlers.rs` says it outright — "payload is empty (`<`/`<=` matching zero
   rows)" — and the scheduler logs `Alert fired without notification (Deliver, payload empty: true)`. Three rules written as `value < threshold` fired seconds
   after being created, with their thresholds nowhere near crossed, told nobody, and then silenced themselves for six to twenty-four hours. **That is the worst
   combination available**: the UI shows a firing, no one is paged, and the silence window swallows the real firing behind it. Every rule here is therefore a
   `>` against an inner `< bool` comparison — `sum(x < bool 15) > 0` is 1 when a real series is below 15, and 0 rather than empty when the series is missing.
5. **A filter on a label the stream does not have is silently ignored**, widening a rule to everything rather than narrowing it to nothing. Measured:
   `count(up)` is 20 — and so are `count(up{job="kube-state-metrics"})` and `count(up{job="nonexistent-xyz"})`, because `job` is not a stored label on `up`. A
   filter on a label that _does_ exist behaves normally: `count(up{service="kube-state-metrics"})` returns nothing. So misspelling the **value** is loud and
   misspelling the **label** is silent, and only one of those is the mistake people make. Every scoping label here was checked by filtering it to a value that
   should match nothing and confirming an empty result.
6. **`trigger_condition.operator`/`threshold` gate on the ROW COUNT, not the value**, and must be `>= 1`. The value comparison has already happened — the
   scheduler rewrites the query as `({promql}) {operator} {value}` and runs that, so what comes back is "the series that crossed the line". Setting this pair to
   the rule's own operator means `ej-importer-stale` asked for **more than one** matching series and never fired, while its query returned 71 hours against a 36
   hour threshold for eight minutes straight. One field changed to `>=` produced `Alert conditions satisfied` → `Alert notification sent` →
   `POST /api/default/alert_history/_json 200` on the very next evaluation.

**What a working firing looks like**, from `alert_history` on 2026-08-23:

```json
{"alert":"ej-importer-stale","stream":"importer_source_last_success","value":"71.94","environment":"staging"}
```

`{alert_name}`, `{stream_name}`, `{org_name}` and `{value}` substitute; **`{timestamp}` does not** — it arrives as the literal string. The ingest timestamp is
already on the row as `_timestamp`.
