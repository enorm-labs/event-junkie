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

| Rule                        | Fires when                                                                                              | #271 item          |
| --------------------------- | ------------------------------------------------------------------------------------------------------- | ------------------ |
| `ej-site-down`              | any scrape target stops answering                                                                       | site down          |
| `ej-importer-stale`         | the stalest source passes 36h against a 24h interval                                                    | importer failing   |
| `ej-source-never-succeeded` | a source has never once completed a run ([#618](https://github.com/enorm-labs/event-junkie/issues/618)) | importer failing   |
| `ej-catalogue-emptying`     | future events fall below 500, from a normal ~3,000                                                      | zero events        |
| `ej-node-disk-filling`      | less than 15% of the node's filesystem is free                                                          | disk filling       |
| `ej-certificate-expiry`     | the soonest certificate is inside 14 days                                                               | certificate expiry |
| `ej-ingest-shedding`        | OpenObserve is rejecting writes ([#625](https://github.com/enorm-labs/event-junkie/issues/625))         | —                  |
| `ej-ingest-queue-saturated` | the collector's export queue is over 80% full                                                           | —                  |

**`ej-catalogue-emptying` is a stand-in and should be read as one.** ADR-015's criterion 1 is per-source — a venue whose scraper still returns 200 while writing
nothing — and the only per-source write signal today is `importer_events_written_total`, a Micrometer counter that resets on every deploy against a 24h import
interval. Closing that needs a database-backed gauge, the way [#618](https://github.com/enorm-labs/event-junkie/issues/618) did for `has_succeeded`. Until then
this catches the same failure one level up.

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

An OpenObserve webhook into `signal-cli-rest-api` **is** item 4's architecture, and it cannot be created today regardless of registration. There are two ways
through (`config.rs:1177`), and they are not equivalent:

|                          |                                                                                      |
| ------------------------ | ------------------------------------------------------------------------------------ |
| `ZO_SSRF_ALLOW_LOOPBACK` | the process may notify **itself** and nothing else. Set, and what `record-only` uses |
| `ZO_SKIP_SSRF_CHECKS`    | removes the check for **every** destination. Not set                                 |

The second is what the Signal route needs, and it is deliberately deferred rather than quietly enabled: the `observability` namespace has **no NetworkPolicies at
all**, so nothing else constrains where this pod may connect. Turning the guard off wholesale lets anyone holding the OpenObserve root credential — which three
other components already hold — point a "destination" at any address in the cluster. **The honest pairing is `ZO_SKIP_SSRF_CHECKS` plus an egress policy**, and
that belongs with the Signal work rather than smuggled in with a set of alert rules.

A destination is mandatory, incidentally: `POST /api/v2/{org}/alerts` with `destinations: []` returns `Alert destination or workflows is required`, with or
without `creates_incident`. So "rules now, delivery later" needs _a_ destination, which is why the loopback one exists.

## This is where GitOps stops, again

Alerts are OpenObserve API objects, not Kubernetes ones, so Flux cannot reconcile them — the same seam `../dashboards/` sits on, with the same consequences:
nothing notices if somebody edits a rule in the UI, and nothing restores these after a node rebuild unless `apply.sh` is run.
[`CLUSTER_BOOTSTRAP.md`](../../docs/ops/CLUSTER_BOOTSTRAP.md) is what has to remember, and it now lists both.

## Two PromQL traps these rules are written around

Both were established by running queries against the live instance, and both fail silently:

1. **`time()` is frozen at the query window's start.** An age is `timestamp(x) - x`. The `time()` form under-reports by the whole window width and goes negative
   for anything newer than the window start.
2. **`count(expr == bool 0)` counts every series, always.** Measured here: `count(up == bool 0)` returns 18 — the number of scrape targets — while
   `sum(up == bool 0)` returns 0, the number that are down. A site-down rule written with `count` fires permanently.
