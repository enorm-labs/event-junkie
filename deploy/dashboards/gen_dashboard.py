#!/usr/bin/env python3
"""Generate the "Is it healthy?" dashboard for OpenObserve v0.92.2 (schema v8).

Written as a generator rather than by hand because the schema repeats a lot of
boilerplate per panel, and a typo in one copy of it is invisible.

Two things about the SCHEMA, neither documented by OpenObserve, both of which
draw a blank or quarter-sized panel rather than raising anything (#969):

  A. The single-value panel type is `metric`. `stat` is Grafana's name for it,
     and OpenObserve accepts it, stores it and draws nothing. The running
     instance's own UI bundle enumerates twenty panel types and `stat` is not
     among them: it contains `"metric"` 31 times and `"stat"` zero.
     `lint_dashboard.py` carries the list, and is what stops it coming back.

  B. The v8 grid is 192 columns wide, not 48. It widened between schema v5 and
     v7 — every reference v3/v5 dashboard ends at `x + w == 48`, every v7/v8 one
     at 192 — and row heights roughly doubled with it. A 48-column layout is
     valid, renders, and fills the left QUARTER of the screen.

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

No panel sets `no_value_replacement`. Drawing a zero where there is no series
would defeat row 4, whose whole job is telling "nothing happened" apart from
"nothing was recorded". A query that cannot report zero on its own is the wrong
query — see `p_shedding`.
"""
import json

STREAM_TYPE = "metrics"

# Schema v8's grid. Panels are placed in these units, not in pixels or fractions.
GRID_WIDTH = 192

# The importer's last-success gauge, with the pod-generation labels collapsed.
LAST_SUCCESS = "max by (source) (importer_source_last_success)"
# Seconds since each source last succeeded. See gotcha 1 for why not `time()`.
AGE = "(timestamp(%s) - %s)" % (LAST_SUCCESS, LAST_SUCCESS)

# The ceiling `p_certs` clamps to, in days. Just above a one-year certificate, which is the
# longest-lived thing anyone here renews; above that line is self-signed internal PKI that renews
# itself. A judgement call rather than a derived number, and the reason for it is in #972.
CERT_CEILING_DAYS = 400

_next_i = iter(range(1, 1000))


def panel(pid, title, description, typ, promql, x, y, w, h, unit=None, decimals=2, y_axis_min=None):
    """One panel. `promql` may be a single query or a list of them."""
    queries = promql if isinstance(promql, list) else [promql]
    cfg = {"show_legends": True, "decimals": decimals}
    if unit:
        cfg["unit"] = unit
    # `y_axis_min` and `y_axis_max` are real keys the chart builder reads. The axis TYPE is not:
    # it is hard-coded to "value", so there is no logarithmic option however much a panel wants one.
    if y_axis_min is not None:
        cfg["y_axis_min"] = y_axis_min
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
    # Quarter width each, on the 192 grid of fact B.
    panel(
        "p_stale_worst",
        "Oldest source (hours since last success)",
        "The single worst venue, against a 24h import interval (`import_interval_minutes = 1440`) — so anything under "
        "24 is routine and the number climbing past ~36 is the signal. ADR-015's zero-events alert uses this series.",
        "metric",
        "max(%s) / 3600" % AGE,
        x=0, y=0, w=48, h=10, decimals=1,
    ),
    panel(
        "p_stale_count",
        "Sources stale > 36h",
        "Venues that have missed a whole import cycle and half of the next. **36h, not 12h** — the interval is 24h, "
        "so a 12h threshold flags all 84 every single day as a matter of routine, which is how a panel teaches people "
        "to ignore it. Uses `> bool` so it shows 0 rather than going blank. A source that has never succeeded is not "
        "counted here and cannot be — it has no age. The panel next to it is where those live.",
        "metric",
        "sum(%s > bool 129600)" % AGE,
        x=48, y=0, w=48, h=10, decimals=0,
    ),
    panel(
        "p_never_succeeded",
        "Sources that have never succeeded",
        "**The blind spot #618 closed.** `last_success` only exists once a source has worked, so a venue that has never "
        "imported had no series at all — not stale, not late, absent. On 2026-08-20 that was 86 sources and 84 series, "
        "and the two missing were the only two that were broken while this dashboard read \"0 sources stale\". "
        "`importer_source_has_succeeded` exists for every enabled row from the first refresh after start-up. "
        "**Anything but 0 is a scraper that has never once worked** — a different fact from a stale one, and a "
        "different response: fix the importer, do not wait for a retry.",
        "metric",
        "sum(max by (source) (importer_source_has_succeeded) == bool 0)",
        x=96, y=0, w=48, h=10, decimals=0,
    ),
    panel(
        "p_future_events",
        "Future events in the database",
        "What the site can actually show. A fall here is the failure mode no HTTP check sees: the importer runs, "
        "reports success, and the listings quietly empty out.",
        "metric",
        'max(db_events{horizon="future"})',
        x=144, y=0, w=48, h=10, decimals=0,
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
        x=0, y=10, w=96, h=18, decimals=1,
    ),
    panel(
        "p_events_trend",
        "Events in the database",
        "Both horizons. `all` only ever grows; `future` is the one that matters and the one that can fall.",
        "line",
        "max by (horizon) (db_events)",
        x=96, y=10, w=96, h=18, decimals=0,
    ),

    # --- Row 3: the platform underneath -----------------------------------
    panel(
        "p_pg",
        "PostgreSQL — database size",
        "Bytes per database. Disk filling is one of #271's five required alerts, and on this node PostgreSQL shares "
        "the disk with everything else.",
        "line",
        "max by (datname) (pg_database_size_bytes)",
        x=0, y=28, w=48, h=16, unit="bytes",
    ),
    panel(
        "p_node_pressure",
        "Node load average and memory utilisation",
        "Two queries, deliberately on one axis: on 2026-08-20 load reached 99 on 2 cores while CPU sat 68% idle. "
        "Load alone reads as a CPU problem; load next to memory utilisation is what identifies it as stalling.",
        "line",
        ["max(system_cpu_load_average_5m)", "max(system_memory_utilization)"],
        x=48, y=28, w=48, h=16,
    ),
    panel(
        "p_node_mem",
        "Node memory available",
        "The node is a cpx22 — 2 vCPU / 4 GB running k3s, two JVMs, PostgreSQL and this observability stack. "
        "It global-OOMed on 2026-08-20 with load at 99. This panel is the one that would have seen it coming.",
        "metric",
        "min(k8s_node_memory_available)",
        x=96, y=28, w=48, h=16, unit="bytes",
    ),
    panel(
        "p_certs",
        "Certificate expiry (days, capped at %d)" % CERT_CEILING_DAYS,
        "cert-manager's own view, per certificate. Expiry is one of #271's five required alerts and the one that "
        "breaks the site without anything crashing.\n\n"
        "**A line resting exactly on %d is a certificate too far out to care about, not one that expires in %d "
        "days.** Without the clamp a single series sets the axis for all of them: staging's self-signed webhook CA "
        "runs for five years, which pushed the Let's Encrypt certificate that actually serves the site into the "
        "bottom 4%% of the panel (#972).\n\n"
        "**Clamped, not filtered, and the distinction is the whole point.** Adding `< %d` to the expression reads "
        "as the obvious fix and silently drops the CA's series instead of flattening it — a certificate missing "
        "from the one panel that watches certificates. `clamp_max` keeps every series: a new certificate always "
        "appears, a long-lived one pins to the ceiling, a short-lived one lands where it can be read."
        % (CERT_CEILING_DAYS, CERT_CEILING_DAYS, CERT_CEILING_DAYS),
        "line",
        "clamp_max((min by (name) (certmanager_certificate_expiration_timestamp_seconds) - timestamp("
        "min by (name) (certmanager_certificate_expiration_timestamp_seconds))) / 86400, %d)" % CERT_CEILING_DAYS,
        x=144, y=28, w=48, h=16, decimals=1, y_axis_min=0,
    ),

    # --- Row 4: whether any of the above can be believed -------------------
    # A gap in every panel above looks identical to a quiet period. These two say which it was.
    panel(
        "p_shedding",
        "Metrics dropped before storage (points/sec)",
        "**Two ways a metric dies on the way in, and neither raises anything.** `send_failed` is OpenObserve "
        "returning 503 — the memtable overflow of #625. `receiver_refused` is the collector declining a point it "
        "cannot place, which is where queue pressure surfaces once the exporter stops accepting. Measured "
        "2026-08-23, before the fix: 4.44M rejected by OpenObserve and 8.45M never queued by the collector, in 48h "
        "against 12.2M delivered — **about half of everything scraped**. Anything but a flat zero here means the "
        "panels above are sampling, not reporting.\n\n"
        "**Both series exist on a healthy collector, and that is the whole requirement.** This panel's second half "
        "was `otelcol_exporter_enqueue_failed_metric_points_total`, which a collector creates only after something "
        "has already failed to enqueue — so it was blank in exactly the case it was meant to certify, and read as a "
        "broken panel rather than a calm one (#969). `deploy/alerts/README.md` records `ej-ingest-shedding` reaching "
        "the same conclusion first: one query per always-present series.",
        "line",
        [
            "sum(rate(otelcol_exporter_send_failed_metric_points_total[5m]))",
            "sum(rate(otelcol_receiver_refused_metric_points_total[5m]))",
        ],
        x=0, y=44, w=96, h=16, decimals=2,
    ),
    panel(
        "p_memtable",
        "OpenObserve memtable (bytes held, against a 256 MiB ceiling)",
        "The gauge the ingest path actually checks — `writer.rs` rejects every write once the sum "
        "across memtables reaches `ZO_MEM_TABLE_MAX_SIZE`, which is a quarter of the container's "
        "memory limit: **268,435,456 bytes** at the current 1Gi. Approaching that line is the "
        "warning the 2026-08-21 outage never gave, because `ZO_PROMETHEUS_ENABLED` was false and "
        "this series did not exist. **Empty until that release reconciles** — the panel is here "
        "because the fix without it is unfalsifiable.",
        "line",
        "max(zo_ingest_memtable_arrow_bytes)",
        x=96, y=44, w=96, h=16, unit="bytes",
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
