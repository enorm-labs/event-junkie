#!/usr/bin/env python3
"""Generate the alert rules #271 asks for, as OpenObserve v2 alert objects.

Written as a generator for the same reason `../dashboards/gen_dashboard.py` is:
the JSON repeats forty lines of scaffolding per rule, and a typo in one copy of
it produces an alert that never fires rather than an error.

## The five #271 requires, and the two that came out of building them

    site down                      -> ej-site-down
    importer failing repeatedly    -> ej-importer-stale
    a source importing zero events -> ej-catalogue-emptying   (aggregate; see below)
    database disk filling          -> ej-node-disk-filling
    certificate expiry             -> ej-certificate-expiry
    a source that never worked     -> ej-source-never-succeeded   (#618)
    metrics being dropped          -> ej-ingest-shedding          (#625)

**The zero-events rule is the aggregate one, and that is a known gap rather than
the finished article.** ADR-015's criterion 1 is per-source: a venue whose
scraper still returns 200 while writing nothing. The only per-source write signal
today is `importer_events_written_total`, a Micrometer counter — it lives in the
process, resets on every deploy, and the import interval is 24h, so
`increase(...[48h]) == 0` cannot tell "wrote nothing" from "restarted". Closing it
needs a database-backed gauge, the way #618 did for `has_succeeded`. Until then
`db_events{horizon="future"}` catches the same failure one level up: the
catalogue emptying out.

## Two PromQL traps, both established by running queries rather than reading docs

  1. `time()` is FROZEN at the query window's start (#610), so an age is
     `timestamp(x) - x`, never `time() - x`. Every staleness rule here depends on
     that, and the wrong form under-reports age by the whole window width.

  2. `count(expr == bool 0)` counts EVERY series, always — it is `sum` that
     counts the matching ones. Measured here: `count(up == bool 0)` returns 18
     (the number of scrape targets) while `sum(up == bool 0)` returns 0 (the
     number that are down). A site-down rule written with `count` fires forever
     and gets muted in week one.

## What "enabled" means before the number exists

Every rule below is `enabled: true` and every rule routes to the `record-only`
destination, which POSTs the firing back into OpenObserve as a log row. That is
deliberate: it makes firing observable now, so the rules are exercised rather
than hypothetical, while delivery waits on the eSIM (#271 item 4). The Signal
destination is one `apply.sh` away once the number registers — see README.md.

**The self-reference is real and is not solved here:** an alert about OpenObserve
being down cannot be recorded by OpenObserve. That is what the external
dead-man's switch (#271 item 5, healthchecks.io) is for, and it is a different
layer on purpose.
"""
import json

ORG = "default"
DESTINATION = "record-only"

# Seconds and minutes, named so the rule table reads as prose rather than arithmetic.
HOUR_S = 3600
DAY_S = 86400

_rules = []


def rule(
    name,
    description,
    promql,
    operator,
    threshold,
    *,
    stream_name,
    period_minutes,
    frequency_seconds,
    silence_minutes,
):
    """One alert.

    `period` is the window the query runs over, in minutes; `frequency` is how
    often it is evaluated, in seconds; `silence` is how long a fired alert stays
    quiet before it can fire again, in minutes — the difference between "the
    database is filling" once an hour and once a minute.

    `stream_name` is required by the API even for a PromQL alert, where the query
    names its own series. It is set to the metric the rule is mostly about, which
    is what the UI groups by.
    """
    _rules.append(
        {
            "name": name,
            "org_id": ORG,
            "stream_type": "metrics",
            "stream_name": stream_name,
            "is_real_time": False,
            "enabled": True,
            "description": description,
            "query_condition": {
                "type": "promql",
                "promql": promql,
                "promql_condition": {"column": "value", "operator": operator, "value": threshold},
            },
            "trigger_condition": {
                "period": period_minutes,
                "frequency": frequency_seconds,
                "frequency_type": "minutes",
                "operator": operator,
                "threshold": 1,
                "silence": silence_minutes,
            },
            "destinations": [DESTINATION],
        }
    )


# --- The site, from inside the cluster ---------------------------------------
#
# `up` is the Prometheus receiver's own per-target health, and it covers the BFF,
# the frontend, Traefik, the database exporter and OpenObserve in one series.
#
# **This is the in-cluster half of "site down" and it cannot be the whole thing** —
# an alerting path that runs on the node it monitors cannot report that node's
# death, which is #271's central caveat. The external probe (#584) is the other
# half and stays dormant until production has DNS.
rule(
    "ej-site-down",
    "One or more scrape targets stopped answering — the BFF, the frontend, Traefik, "
    "PostgreSQL's exporter or OpenObserve itself. `sum`, not `count`: count returns the "
    "number of targets that exist, which is 18 and never zero.",
    "sum(up == bool 0)",
    ">",
    0,
    stream_name="up",
    period_minutes=5,
    frequency_seconds=60,
    silence_minutes=30,
)

# --- The importer, which is what the project is for ---------------------------
#
# 36h rather than 24h for the reason #617 gives about the dashboard panel: the
# interval IS 24h, so a 24h threshold flags the whole catalogue every day as a
# matter of routine, and an alert that fires every day is an alert that gets
# muted. 36h is "missed a whole cycle and half of the next".
rule(
    "ej-importer-stale",
    "The stalest source has not had a successful run in 36 hours, against a 24h import "
    "interval. One venue is a scraper to fix; all of them together is the importer or the "
    "database. `timestamp(x) - x` because OpenObserve freezes `time()` at the window start.",
    "max((timestamp(max by (source) (importer_source_last_success)) "
    "- max by (source) (importer_source_last_success)) / 3600)",
    ">",
    36,
    stream_name="importer_source_last_success",
    period_minutes=10,
    frequency_seconds=5 * 60,
    silence_minutes=12 * 60,
)

# The #618 gauge. A source that has never succeeded has no `last_success` series
# at all, so the rule above is structurally blind to it — which is the whole
# reason `has_succeeded` exists.
rule(
    "ej-source-never-succeeded",
    "A source has been enabled long enough to be counted and has never once completed a "
    "run. Different from stale and needing a different response: fix the scraper, do not "
    "wait for the retry. Blind spot closed by #618.",
    "sum(max by (source) (importer_source_has_succeeded) == bool 0)",
    ">",
    0,
    stream_name="importer_source_has_succeeded",
    period_minutes=15,
    frequency_seconds=30 * 60,
    silence_minutes=24 * 60,
)

# The aggregate stand-in for ADR-015 criterion 1 — see the module docstring for
# why the per-source version does not exist yet.
rule(
    "ej-catalogue-emptying",
    "Future events have fallen below 500, from a normal ~3,000. This is the failure no "
    "HTTP check sees: scrapers return 200, runs report success, and the listings empty out "
    "over a fortnight. The per-source version of this needs a database-backed events "
    "counter and does not exist yet.",
    'max(db_events{horizon="future"})',
    "<",
    500,
    stream_name="db_events",
    period_minutes=30,
    frequency_seconds=10 * 60,
    silence_minutes=12 * 60,
)

# --- The platform underneath --------------------------------------------------
#
# The node's filesystem rather than `pg_database_size_bytes`: the database is
# 18 MB and the disk is 75 GB, so what actually fills this node is Parquet, WAL
# and container logs. A ratio rather than bytes, so it survives a resize.
rule(
    "ej-node-disk-filling",
    "Less than 15% of the node's filesystem is free. The database is a rounding error here "
    "— what fills this disk is OpenObserve's WAL, container logs and images. A ratio, not "
    "a byte count, so a node resize does not silently invalidate the threshold.",
    "min(k8s_node_filesystem_available / k8s_node_filesystem_capacity) * 100",
    "<",
    15,
    stream_name="k8s_node_filesystem_available",
    period_minutes=15,
    frequency_seconds=5 * 60,
    silence_minutes=6 * 60,
)

# 14 days is two full renewal attempts' worth of warning: cert-manager renews at
# 30 days remaining, so anything still inside 14 has already failed twice.
rule(
    "ej-certificate-expiry",
    "The soonest-expiring certificate is inside 14 days. cert-manager renews at 30 days "
    "remaining, so reaching 14 means renewal has already failed — silently, because nothing "
    "crashes when a certificate ages.",
    "min((certmanager_certificate_expiration_timestamp_seconds "
    "- timestamp(certmanager_certificate_expiration_timestamp_seconds)) / 86400)",
    "<",
    14,
    stream_name="certmanager_certificate_expiration_timestamp_seconds",
    period_minutes=60,
    frequency_seconds=HOUR_S,
    silence_minutes=24 * 60,
)

# --- Whether any of the above can be believed ---------------------------------
#
# #625: for days, roughly half of all metric points were dropped before storage,
# and every rule above would have gone quiet without anything looking wrong. An
# alert set that cannot tell "quiet" from "blind" is worse than none.
#
# **These are two rules because the obvious one-rule version cannot fire.** The
# first draft was
#
#     sum(rate(send_failed[5m])) + sum(rate(enqueue_failed[5m]))
#
# and `--check` returned NO DATA for it while both halves were healthy. The reason
# is worth keeping: a collector only exports `enqueue_failed` once something has
# actually failed to enqueue, so after the gateway restarts the series does not
# exist — and a binary operation with an empty side yields an empty result, not
# the other side's value. **The composite rule is therefore un-fireable during
# exactly the normal operation it is supposed to watch**, and would have looked
# fine in the UI. Measured: the sum returned 0 series over the rule's own 15
# minute window; `send_failed` alone returned 16 points over the same window.
#
# So: one rule per always-present series. Rejects on one side, backpressure on the
# other, and queue fill is the leading indicator of the enqueue failures that the
# absent counter would have reported too late.
rule(
    "ej-ingest-shedding",
    "OpenObserve is rejecting writes. That means the other rules are evaluating a sample "
    "rather than the data — a gap that looks exactly like a healthy quiet period. #625 ran "
    "at ~51% loss for days before anybody noticed, and none of these rules would have said so.",
    "sum(rate(otelcol_exporter_send_failed_metric_points_total[5m]))",
    ">",
    0,
    stream_name="otelcol_exporter_send_failed_metric_points_total",
    period_minutes=15,
    frequency_seconds=5 * 60,
    silence_minutes=2 * 60,
)

rule(
    "ej-ingest-queue-saturated",
    "The collector's export queue is over 80% full — backpressure, and the step before it "
    "starts dropping points it can never retry. Both series here exist at all times, which "
    "is why this watches queue depth rather than the enqueue-failure counter that only "
    "appears after the damage.",
    "max(otelcol_exporter_queue_size) / max(otelcol_exporter_queue_capacity) * 100",
    ">",
    80,
    stream_name="otelcol_exporter_queue_size",
    period_minutes=15,
    frequency_seconds=5 * 60,
    silence_minutes=2 * 60,
)


print(json.dumps(_rules, indent=2))
