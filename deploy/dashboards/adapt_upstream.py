#!/usr/bin/env python3
"""Adapt openobserve/dashboards' "OpenObserve Internals" to this deployment.

    python3 adapt_upstream.py <upstream.json> > openobserve-internals.json

**A script rather than a hand-edited file, so the adaptation survives a refresh.** Upstream keeps
changing and the edits below are mechanical; written down they are a re-run, and hand-applied they
are archaeology. Same reasoning as the `VENDORED.md` files under `.claude/skills/`, which record the
upstream commit and the command that refreshes it. `VENDORED.md` beside this script holds both.

Four things have to change, and each was established against the live instance:

  1. **The layout is on a 48-column grid and the UI draws on 192.** Upstream is schema v5. The UI
     initialises GridStack with `column:192` unconditionally — there is no version check — so a v5
     layout renders in the left quarter of the screen. Since the layout has to be rewritten anyway,
     the file also declares v8, which costs nothing and keeps `lint_dashboard.py` simple. Heights
     double, matching the ratio between v5 and v8 dashboards upstream.

  2. **The namespace variable reads a stream we do not have.** It takes its values from
     `container_cpu_utilization`; the collector here emits `container_cpu_usage`. Left alone the
     variable offers nothing, every query filtering `namespace="$namespace"` gets an empty value,
     and the whole dashboard is blank in a way that reads as a metrics fault. It now reads a `zo_*`
     stream, so it can only ever offer namespaces where OpenObserve actually runs.

  3. **`pod=~".*querier.*"` matches nothing here.** That is a distributed deployment's querier pods.
     This one runs `ZO_LOCAL_MODE=true`: one pod, `role="all"`.

  5. **Some panels resolve and are still empty**, because they describe a topology this deployment
     does not have. `DISTRIBUTED_ONLY_PANELS` carries them and the measurement behind each.

  4. **The memory-cache gauges do not exist.** Measured, not assumed: after a real search through
     the API the *disk* cache gauges are present and `zo_query_memory_cache_used_bytes` and
     `_files` are still absent, so the memory cache is not in use. `..._limit_bytes` is present and
     reports the configured limit, which is what makes this look like a scrape gap. The disk-cache
     panels in the same tab answer the same question, so the memory ones are dropped rather than
     left to render blank.
"""
import json
import re
import sys

# Upstream's grid, and the one the UI actually draws on. See point 1.
UPSTREAM_GRID = 48
TARGET_GRID = 192
SCALE = TARGET_GRID // UPSTREAM_GRID
HEIGHT_SCALE = 2

# The stream the namespace variable reads instead of `container_cpu_utilization`. Any always-present
# `zo_*` metric would do; this one is a gauge the ingest path writes continuously.
VARIABLE_STREAM = "zo_ingest_wal_used_bytes"
VARIABLE_FIELD = "namespace"

# Gauges this build does not export. See point 4.
ABSENT_METRICS = ("zo_query_memory_cache_used_bytes", "zo_query_memory_cache_files")

# Panels whose metrics exist and whose queries resolve, and which are still empty here. gRPC is the
# hot path BETWEEN OpenObserve nodes; with one pod it carries almost nothing, so `irate` over its
# histogram is flat and `histogram_quantile` of a flat histogram is NaN. Measured over six hours:
# the p95 series had **1 real point in 73**, against 73 of 73 for the HTTP panel beside it. A panel
# that is NaN 99% of the time is a blank panel with extra steps.
DISTRIBUTED_ONLY_PANELS = ("gRPC API latency",)

ROLE_FILTER = re.compile(r',\s*pod=~"\.\*[a-z]+\.\*"')

BANNER = """### OpenObserve internals

The process's own view of itself — write-ahead log, compaction, storage, ingestion, query cache and
API. Adapted from [openobserve/dashboards](https://github.com/openobserve/dashboards) by
`deploy/dashboards/adapt_upstream.py`; edit that, not this.

**This is a standalone deployment** — `ZO_LOCAL_MODE=true`, one pod, `role="all"`. Upstream's
per-role panels have been removed rather than left to draw nothing.

_"Is it healthy?" answers whether anything is wrong. This answers why, when the answer is
OpenObserve itself._"""


def scale_layout(layout):
    """48-column coordinates to 192, and heights to the taller v8 rows."""
    out = dict(layout)
    out["x"] = layout["x"] * SCALE
    out["w"] = layout["w"] * SCALE
    # `y` scales with `h` and for the same reason. Doubling a row's height without moving the row
    # below it down by the same amount drops every panel onto the one above it — caught by
    # lint_dashboard.py's overlap rule rather than by looking at the rendered page.
    out["y"] = layout["y"] * HEIGHT_SCALE
    out["h"] = layout["h"] * HEIGHT_SCALE
    # Upstream's banner is `w: 47` where every other full-width row is 48 — its own rounding slip,
    # and the reason that tab measures one column short. Snap a panel that was within a column of
    # the right edge back to it, so the grid rule in lint_dashboard.py sees a full-width dashboard.
    if layout["x"] + layout["w"] == UPSTREAM_GRID - 1:
        out["w"] = TARGET_GRID - out["x"]
    return out


def adapt_query(query):
    """Drop the role filter from a query."""
    out = dict(query)
    out["query"] = ROLE_FILTER.sub("", query.get("query") or "")
    return out


def is_dead(panel):
    """True when any query needs a metric this build does not export.

    **Any, not all.** A panel is a designed comparison — cache limit against cache used — and
    keeping the half that resolves leaves a flat line at a constant, which reads as a working panel
    reporting nothing wrong. Dropping the whole panel is the honest half of the same choice.
    """
    return any(
        m in (q.get("query") or "") for q in panel.get("queries") or [] for m in ABSENT_METRICS
    )


def reflow(panels):
    """Widen a row whose panels no longer fill the grid, after one of them was dropped.

    Only a short row is touched, and its panels keep their proportions to each other — upstream
    puts a wide panel beside a narrow one deliberately, and squaring them all off would lose that.
    Rows are keyed on `y`, which holds because upstream lays out in clean rows; where it does not,
    `lint_dashboard.py`'s overlap rule is what says so.
    """
    rows = {}
    for panel in panels:
        rows.setdefault(panel["layout"]["y"], []).append(panel)
    for row in rows.values():
        row.sort(key=lambda p: p["layout"]["x"])
        right = max(p["layout"]["x"] + p["layout"]["w"] for p in row)
        if right == TARGET_GRID:
            continue
        x = 0
        for i, panel in enumerate(row):
            width = round(panel["layout"]["w"] * TARGET_GRID / right)
            panel["layout"]["x"] = x
            panel["layout"]["w"] = TARGET_GRID - x if i == len(row) - 1 else width
            x += width
    return panels


def adapt(dash):
    dash = json.loads(json.dumps(dash))
    dash["version"] = 8

    for variable in (dash.get("variables") or {}).get("list") or []:
        data = variable.get("query_data") or {}
        if data.get("stream"):
            data["stream"] = VARIABLE_STREAM
            data["field"] = VARIABLE_FIELD

    for tab in dash.get("tabs") or []:
        kept = []
        for panel in tab.get("panels") or []:
            panel["layout"] = scale_layout(panel["layout"])
            if panel.get("type") == "markdown":
                panel["markdownContent"] = BANNER
                kept.append(panel)
                continue
            if is_dead(panel) or panel.get("title") in DISTRIBUTED_ONLY_PANELS:
                continue
            panel["queries"] = [adapt_query(q) for q in panel.get("queries") or []]
            kept.append(panel)
        tab["panels"] = reflow(kept)

    dash["description"] = (
        "OpenObserve's own internals. Adapted from openobserve/dashboards by "
        "deploy/dashboards/adapt_upstream.py — edit that, not this."
    )
    return dash


def main(argv):
    if len(argv) != 2:
        print("usage: adapt_upstream.py <upstream.json>", file=sys.stderr)
        return 2
    with open(argv[1]) as f:
        print(json.dumps(adapt(json.load(f)), indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
