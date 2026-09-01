#!/usr/bin/env python3
"""Adapt dashboards from openobserve/dashboards to this deployment.

    git clone --depth 1 https://github.com/openobserve/dashboards.git /tmp/o2dash
    python3 adapt_upstream.py /tmp/o2dash        # rewrites every file in DASHBOARDS

**A script rather than hand-edited files, so the adaptation survives a refresh.** Upstream keeps
changing and the edits below are mechanical; written down they are a re-run, and hand-applied they
are archaeology. Same reasoning as the `VENDORED.md` files under `.claude/skills/`, which record the
upstream commit and the command that refreshes it. `VENDORED.md` beside this script holds both, and
says which upstream dashboards are deliberately not here.

Two changes apply to every dashboard, and both are invisible until someone looks at the rendered
page:

  1. **The layout is on a 48-column grid and the UI draws on 192.** Upstream is schema v5. The UI
     initialises GridStack with `column:192` unconditionally — there is no version check — so a v5
     layout renders in the left quarter of the screen. Since the layout has to be rewritten anyway,
     the files also declare v8, which costs nothing and keeps `lint_dashboard.py` simple. Heights
     and `y` both double, matching the ratio between v5 and v8 dashboards upstream. `y` scales with
     `h` for the obvious reason: doubling a row's height without moving the row below it drops every
     panel onto the one above it.

  2. **Dropping a panel leaves its row short of the right edge**, so short rows are re-flowed. Only
     short rows, and proportionally — upstream puts a wide panel beside a narrow one deliberately.

The rest is per dashboard, in DASHBOARDS below. Every drop is there because the panel cannot have
data here, measured against the live instance rather than assumed, and the note beside it says what
was measured. A panel that renders blank is worse than an absent one: it teaches people that blank
panels are normal, which is the argument `README.md` makes at length.
"""
import json
import os
import re
import sys

# Upstream's grid, and the one the UI actually draws on.
UPSTREAM_GRID = 48
TARGET_GRID = 192
SCALE = TARGET_GRID // UPSTREAM_GRID
HEIGHT_SCALE = 2

INTERNALS_BANNER = """### OpenObserve internals

The process's own view of itself — write-ahead log, compaction, storage, ingestion, query cache and
API. Adapted from [openobserve/dashboards](https://github.com/openobserve/dashboards) by
`deploy/dashboards/adapt_upstream.py`; edit that, not this.

**This is a standalone deployment** — `ZO_LOCAL_MODE=true`, one pod, `role="all"`. Upstream's
per-role panels have been removed rather than left to draw nothing.

_"Is it healthy?" answers whether anything is wrong. This answers why, when the answer is
OpenObserve itself._"""

DASHBOARDS = [
    {
        "source": "OpenObserve/OpenObserve Internals.dashboard.json",
        "output": "openobserve-internals.json",
        "banner": INTERNALS_BANNER,
        "description": (
            "OpenObserve's own internals. Adapted from openobserve/dashboards by "
            "deploy/dashboards/adapt_upstream.py — edit that, not this."
        ),
        # The namespace variable reads `container_cpu_utilization`; the collector here emits
        # `container_cpu_usage`. Left alone the variable offers nothing, every query filtering
        # `namespace="$namespace"` gets an empty value, and the dashboard is blank in a way that
        # reads as a metrics fault rather than a variable one. Any always-present `zo_*` stream
        # does; this one is a gauge the ingest path writes continuously.
        "variable_source": ("zo_ingest_wal_used_bytes", "namespace"),
        # Gauges this build does not export. Measured: after a real search through the API the disk
        # cache gauges are present and these are still absent, so the memory cache is not in use.
        # `..._limit_bytes` is present and reports the configured limit, which is what makes this
        # look like a scrape gap.
        "absent_metrics": ("zo_query_memory_cache_used_bytes", "zo_query_memory_cache_files"),
        # Metrics exist, queries resolve, panel is still empty. gRPC is the hot path BETWEEN
        # OpenObserve nodes; with one pod it carries almost nothing, so `irate` over its histogram
        # is flat and `histogram_quantile` of a flat histogram is NaN. Over six hours the p95 series
        # had 1 real point in 73, against 73 of 73 for the HTTP panel beside it.
        "drop_panels": ("gRPC API latency",),
        # `pod=~".*querier.*"` is a distributed deployment's querier pods.
        "drop_role_filter": True,
    },
    {
        "source": "Kubernetes(openobserve-collector)/Kubernetes _ Events.dashboard.json",
        "output": "kubernetes-events.json",
        "title": "Kubernetes / Events",
        "description": (
            "What Kubernetes itself is complaining about. Adapted from openobserve/dashboards by "
            "deploy/dashboards/adapt_upstream.py — edit that, not this."
        ),
    },
    {
        "source": "Kubernetes(openobserve-collector)/Kubernetes  _ Namespaces.dashboard.json",
        "output": "kubernetes-namespaces.json",
        "title": "Kubernetes / Namespaces",
        "description": (
            "Which namespace is using the node. Adapted from openobserve/dashboards by "
            "deploy/dashboards/adapt_upstream.py — edit that, not this."
        ),
        # Both filter `container_fs_*` by a device regex for SD cards and eMMC, which is not what
        # this node boots from, and both are the only queries carrying Grafana's `__rate_interval`.
        # Neither returned a series over six hours.
        "drop_panels": ("Storage: IOPS(Reads+Writes)", "Storage: ThroughPut(Read+Write)"),
    },
]


# `pod=~".*querier.*"` and its siblings, which name a distributed deployment's roles.
ROLE_FILTER = re.compile(r',\s*pod=~"\.\*[a-z]+\.\*"')


def drop_role_filter(query):
    return ROLE_FILTER.sub("", query)


def scale_layout(layout):
    """48-column coordinates to 192, and rows to the taller v8 grid."""
    out = dict(layout)
    out["x"] = layout["x"] * SCALE
    out["w"] = layout["w"] * SCALE
    out["y"] = layout["y"] * HEIGHT_SCALE
    out["h"] = layout["h"] * HEIGHT_SCALE
    # Upstream's banner is `w: 47` where every other full-width row is 48 — its own rounding slip,
    # and the reason that tab measures one column short. Snap a panel that was within a column of
    # the right edge back to it, so the grid rule in lint_dashboard.py sees a full-width dashboard.
    if layout["x"] + layout["w"] == UPSTREAM_GRID - 1:
        out["w"] = TARGET_GRID - out["x"]
    return out


def reflow(panels):
    """Widen a row whose panels no longer fill the grid, after one of them was dropped.

    Only a short row is touched, and its panels keep their proportions to each other. Rows are keyed
    on `y`, which holds because upstream lays out in clean rows; where it does not,
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


def strip_unstored_keys(panel):
    """Remove field keys OpenObserve drops when it stores the dashboard.

    `aggregationFunction` on a SQL panel's x/y fields is not persisted — the panel round-trips
    without it. Keeping it in the file makes `apply.sh --diff` report the dashboard as differing
    from the cluster **immediately after importing it**, for ever, which is precisely how a check
    teaches people to ignore it. The generated `query` string is what the panel actually runs.
    """
    for query in panel.get("queries") or []:
        for axis in ("x", "y", "z", "breakdown"):
            for field in (query.get("fields") or {}).get(axis) or []:
                field.pop("aggregationFunction", None)
    return panel


def is_dead(panel, absent):
    """True when any query needs a metric this build does not export.

    **Any, not all.** A panel is a designed comparison — cache limit against cache used — and
    keeping the half that resolves leaves a flat line at a constant, which reads as a working panel
    reporting nothing wrong. Dropping the whole panel is the honest half of the same choice.
    """
    return any(m in (q.get("query") or "") for q in panel.get("queries") or [] for m in absent)


def adapt(dash, spec):
    dash = json.loads(json.dumps(dash))
    dash["version"] = 8
    if spec.get("title"):
        dash["title"] = spec["title"]
    dash["description"] = spec["description"]

    source = spec.get("variable_source")
    if source:
        for variable in (dash.get("variables") or {}).get("list") or []:
            data = variable.get("query_data") or {}
            if data.get("stream"):
                data["stream"], data["field"] = source

    absent = spec.get("absent_metrics", ())
    dropped = spec.get("drop_panels", ())
    for tab in dash.get("tabs") or []:
        kept = []
        for panel in tab.get("panels") or []:
            panel["layout"] = scale_layout(panel["layout"])
            if panel.get("type") == "markdown":
                if spec.get("banner"):
                    panel["markdownContent"] = spec["banner"]
                kept.append(panel)
                continue
            if panel.get("title") in dropped or is_dead(panel, absent):
                continue
            if spec.get("drop_role_filter"):
                for query in panel.get("queries") or []:
                    query["query"] = drop_role_filter(query.get("query") or "")
            kept.append(strip_unstored_keys(panel))
        tab["panels"] = reflow(kept)
    return dash


def main(argv):
    if len(argv) != 2:
        print("usage: adapt_upstream.py <path to an openobserve/dashboards clone>", file=sys.stderr)
        return 2
    root, here = argv[1], os.path.dirname(os.path.abspath(__file__))
    for spec in DASHBOARDS:
        path = os.path.join(root, spec["source"])
        if not os.path.exists(path):
            print("missing upstream file: %s" % path, file=sys.stderr)
            return 2
        with open(path) as f:
            adapted = adapt(json.load(f), spec)
        out = os.path.join(here, spec["output"])
        with open(out, "w") as f:
            f.write(json.dumps(adapted, indent=2) + "\n")
        panels = sum(len(t["panels"]) for t in adapted["tabs"])
        print("%-30s %2d panels  <- %s" % (spec["output"], panels, spec["source"]))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
