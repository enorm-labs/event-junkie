#!/usr/bin/env python3
"""Generate the "Is it healthy?" dashboard for OpenObserve v0.92.2 (schema v8).

Written as a generator rather than by hand because the schema repeats a lot of
boilerplate per panel, and a typo in one copy of it is invisible.

Three things about OpenObserve's PromQL were established by running queries
against the live instance, not by reading documentation, and each one silently
produces a wrong or blank panel rather than an error:

  1. `time()` is FROZEN AT THE QUERY WINDOW'S START, not the evaluation step.
     Measured: over a 6h window it returned the same value at every step. So
     `time() - some_epoch_gauge` under-reports age by the whole window width and
     goes NEGATIVE for anything newer than the window start. Use
     `timestamp(x) - x` instead, which is per-step and correct.

  2. `sort` and `sort_desc` are NOT IMPLEMENTED — the query errors out. Use
     `topk`/`bottomk`, which are.

  3. `or vector(0)` does NOT reliably backfill an empty result. The idiom that
     does is `sum(expr > bool N)`: the `bool` modifier yields 0/1 for every
     series, so the sum is defined even when nothing crosses the threshold.
     Note `count(expr > bool N)` is a trap — it counts every series, always.

And one thing about the data rather than the query language: every metric
arrives carrying ~29 OTel resource labels including `k8s_pod_name`,
`k8s_pod_uid` and `container_image_tag`. A pod restart or a deploy therefore
starts a BRAND NEW series. Every query below aggregates those away with
`max by (<real label>)` first; without it a bare selector returns one series per
pod generation and an instant query can return nothing at all.
"""
import json

STREAM_TYPE = "metrics"

# The importer's last-success gauge, with the pod-generation labels collapsed.
LAST_SUCCESS = "max by (source) (importer_source_last_success)"
# Seconds since each source last succeeded. See gotcha 1 for why not `time()`.
AGE = "(timestamp(%s) - %s)" % (LAST_SUCCESS, LAST_SUCCESS)

_next_i = iter(range(1, 1000))


def panel(pid, title, description, typ, promql, x, y, w, h, unit=None, decimals=2):
    """One panel. `promql` may be a single query or a list of them."""
    queries = promql if isinstance(promql, list) else [promql]
    cfg = {"show_legends": True, "decimals": decimals}
    if unit:
        cfg["unit"] = unit
    return {
        "id": pid,
        "type": typ,
        "title": title,
        "description": description,
        "config": cfg,
        "queryType": "promql",
        "queries": [
            {
                "query": q,
                "vrlFunctionQuery": None,
                "customQuery": True,
                "fields": {
                    "stream": "",
                    "stream_type": STREAM_TYPE,
                    "x": [], "y": [], "z": [], "breakdown": [],
                    "filter": {"filterType": "group", "logicalOperator": "AND", "conditions": []},
                },
                "config": {"promql_legend": "", "layer_type": "scatter", "weight_fixed": 1},
            }
            for q in queries
        ],
        "layout": {"x": x, "y": y, "w": w, "h": h, "i": next(_next_i)},
    }


panels = [
    # --- Row 1: the four numbers that answer the question -----------------
    panel(
        "p_stale_worst",
        "Oldest source (hours since last success)",
        "The single worst venue, against a 24h import interval (`import_interval_minutes = 1440`) — so anything under "
        "24 is routine and the number climbing past ~36 is the signal. ADR-015's zero-events alert uses this series.",
        "stat",
        "max(%s) / 3600" % AGE,
        x=0, y=0, w=12, h=4, decimals=1,
    ),
    panel(
        "p_stale_count",
        "Sources stale > 36h",
        "Venues that have missed a whole import cycle and half of the next. **36h, not 12h** — the interval is 24h, "
        "so a 12h threshold flags all 84 every single day as a matter of routine, which is how a panel teaches people "
        "to ignore it. Uses `> bool` so it shows 0 rather than going blank. NOTE: a source that has NEVER succeeded is "
        "invisible here — see the dashboard README.",
        "stat",
        "sum(%s > bool 129600)" % AGE,
        x=12, y=0, w=12, h=4, decimals=0,
    ),
    panel(
        "p_future_events",
        "Future events in the database",
        "What the site can actually show. A fall here is the failure mode no HTTP check sees: the importer runs, "
        "reports success, and the listings quietly empty out.",
        "stat",
        'max(db_events{horizon="future"})',
        x=24, y=0, w=12, h=4, decimals=0,
    ),
    panel(
        "p_node_mem",
        "Node memory available",
        "The node is a cpx22 — 2 vCPU / 4 GB running k3s, two JVMs, PostgreSQL and this observability stack. "
        "It global-OOMed on 2026-08-20 with load at 99. This panel is the one that would have seen it coming.",
        "stat",
        "min(k8s_node_memory_available)",
        x=36, y=0, w=12, h=4, unit="bytes",
    ),

    # --- Row 2: the importer, which is what the project is for ------------
    panel(
        "p_stale_by_source",
        "Twenty stalest sources (hours)",
        "`topk` rather than `sort_desc`, which OpenObserve does not implement. Against a 24h interval, a flat band "
        "under 24 is health. One venue alone above it is a scraper to fix; all of them together is the importer or "
        "the database.",
        "bar",
        "topk(20, %s / 3600)" % AGE,
        x=0, y=4, w=24, h=8, decimals=1,
    ),
    panel(
        "p_events_trend",
        "Events in the database",
        "Both horizons. `all` only ever grows; `future` is the one that matters and the one that can fall.",
        "line",
        "max by (horizon) (db_events)",
        x=24, y=4, w=24, h=8, decimals=0,
    ),

    # --- Row 3: the platform underneath -----------------------------------
    panel(
        "p_pg",
        "PostgreSQL — database size",
        "Bytes per database. Disk filling is one of #271's five required alerts, and on this node PostgreSQL shares "
        "the disk with everything else.",
        "line",
        "max by (datname) (pg_database_size_bytes)",
        x=0, y=12, w=16, h=7, unit="bytes",
    ),
    panel(
        "p_node_pressure",
        "Node load average and memory utilisation",
        "Two queries, deliberately on one axis: on 2026-08-20 load reached 99 on 2 cores while CPU sat 68% idle. "
        "Load alone reads as a CPU problem; load next to memory utilisation is what identifies it as stalling.",
        "line",
        ["max(system_cpu_load_average_5m)", "max(system_memory_utilization)"],
        x=16, y=12, w=16, h=7,
    ),
    panel(
        "p_certs",
        "Certificate expiry (days)",
        "cert-manager's own view, per certificate. Expiry is one of #271's five required alerts and the one that "
        "breaks the site without anything crashing.",
        "line",
        "(min by (name) (certmanager_certificate_expiration_timestamp_seconds) - timestamp("
        "min by (name) (certmanager_certificate_expiration_timestamp_seconds))) / 86400",
        x=32, y=12, w=16, h=7, decimals=1,
    ),
]

dashboard = {
    "version": 8,
    "dashboardId": "",
    "title": "Is it healthy?",
    "description": (
        "#271's one-screen answer. The top row is the answer; everything below it is why. "
        "Generated from deploy/dashboards/gen_dashboard.py — edit that, not this."
    ),
    "role": "",
    "owner": "",
    "created": "2026-08-20T16:00:00+00:00",
    "tabs": [{"tabId": "default", "name": "Overview", "panels": panels}],
    "variables": None,
    "defaultDatetimeDuration": {
        "type": "relative",
        "relativeTimePeriod": "6h",
        "startTime": None,
        "endTime": None,
    },
    "updatedAt": 0,
}

print(json.dumps(dashboard, indent=2))
