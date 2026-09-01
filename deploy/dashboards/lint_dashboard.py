#!/usr/bin/env python3
"""Check a dashboard against what OpenObserve will actually draw. No network.

`check_panels.py` asks whether a panel's QUERY returns rows. That is a different
question from whether OpenObserve can render the panel, and #969 is what the gap
costs: five panels typed `stat` — Grafana's name for `metric` — returned data
from every query and drew nothing, while `--check` reported them green. The same
run had all twelve panels laid out on a 48-column grid that schema v8 widened to
192, so the dashboard occupied a quarter of the screen.

Both are silent. OpenObserve stores an unknown panel type and an off-grid layout
without complaint, and the UI has no error state for either — it draws nothing,
or it draws something small. So the check has to be static, and it has to run
before anything reaches the network. `apply.sh` runs it on every invocation.

    python3 lint_dashboard.py is-it-healthy.json
"""
import json
import sys

# Schema v8. Both numbers are load-bearing and neither is in OpenObserve's docs.
# The grid width is derived in gen_dashboard.py's docstring from the 69 dashboards
# in openobserve/dashboards, which is the only corpus of known-good layouts there
# is.
SCHEMA_VERSION = 8
GRID_WIDTH = 192

# The twenty types the UI implements, read out of the running instance's own
# bundle (`/web/assets/index-*.js`) rather than inferred from example dashboards:
#
#     grep -oE '\["area","line",[^]]*\]' index-*.js
#
# That is the authority, and it is worth preferring over the docs and over the
# example corpus alike — the same bundle contains `"metric"` 31 times and `"stat"`
# zero, which is the whole of #969 in one line. Re-read it after an upgrade; a
# type outside this set fails rather than warns, because the failure it is there
# to catch renders as an empty rectangle and nothing else.
PANEL_TYPES = {
    "area", "area-stacked", "bar", "custom_chart", "donut", "gauge", "geomap",
    "h-bar", "h-stacked", "heatmap", "html", "line", "maps", "markdown",
    "metric", "pie", "sankey", "scatter", "stacked", "table",
}

# Panel types that draw from a query rather than from static content.
CONTENT_TYPES = {"markdown", "html"}


def overlaps(a, b):
    """True when two layout rectangles share any area."""
    return (
        a["x"] < b["x"] + b["w"]
        and b["x"] < a["x"] + a["w"]
        and a["y"] < b["y"] + b["h"]
        and b["y"] < a["y"] + a["h"]
    )


def lint(dash):
    """Return a list of human-readable problems. Empty means the dashboard is drawable."""
    problems = []

    if dash.get("version") != SCHEMA_VERSION:
        problems.append("dashboard version is %r, expected %d" % (dash.get("version"), SCHEMA_VERSION))

    tabs = dash.get("tabs") or []
    if not any(t.get("panels") for t in tabs):
        problems.append("dashboard has no panels")

    seen_ids = {}

    # **Per tab, because each tab is its own grid.** A dashboard's tabs all start at y=0 and share
    # coordinates, so a check that accumulates across them reports every tab overlapping every other
    # one. `is-it-healthy.json` has a single tab and never exposed this; the first eight-tab
    # dashboard produced 116 false findings (#971). `layout.i` repeats across tabs for the same
    # reason and is deliberately not checked for uniqueness — `id` is, and that one is global.
    for tab_data in tabs:
        tab = tab_data.get("name", "?")
        placed = []
        for p in tab_data.get("panels") or []:
            name = p.get("title") or p.get("id") or "<untitled>"
            where = "%s / %s" % (tab, name)

            typ = p.get("type")
            if typ not in PANEL_TYPES:
                hint = " (OpenObserve calls it 'metric')" if typ == "stat" else ""
                problems.append("%s: panel type %r is not one OpenObserve draws%s" % (where, typ, hint))

            if p.get("id") in seen_ids:
                problems.append("%s: duplicate panel id %r, already used by %r" % (where, p.get("id"), seen_ids[p["id"]]))
            else:
                seen_ids[p.get("id")] = name

            layout = p.get("layout") or {}
            missing = [k for k in ("x", "y", "w", "h", "i") if not isinstance(layout.get(k), int)]
            if missing:
                problems.append("%s: layout is missing integer %s" % (where, ", ".join(missing)))
            else:
                if layout["w"] < 1 or layout["h"] < 1:
                    problems.append("%s: layout w=%d h=%d, both must be positive" % (where, layout["w"], layout["h"]))
                if layout["x"] < 0 or layout["y"] < 0:
                    problems.append("%s: layout x=%d y=%d, neither may be negative" % (where, layout["x"], layout["y"]))
                right = layout["x"] + layout["w"]
                if right > GRID_WIDTH:
                    problems.append(
                        "%s: layout reaches column %d, past the %d-column grid (x=%d w=%d)"
                        % (where, right, GRID_WIDTH, layout["x"], layout["w"])
                    )
                for other_where, other in placed:
                    if overlaps(layout, other):
                        problems.append("%s: layout overlaps %s" % (where, other_where))
                placed.append((where, layout))

            if typ in CONTENT_TYPES:
                continue

            queries = p.get("queries") or []
            if not queries:
                problems.append("%s: has no queries" % where)
            for n, q in enumerate(queries):
                if not (q.get("query") or "").strip():
                    problems.append("%s: query %d is empty" % (where, n))
                if p.get("queryType") == "promql":
                    stream_type = (q.get("fields") or {}).get("stream_type")
                    if stream_type != "metrics":
                        problems.append(
                            "%s: query %d is promql but stream_type is %r, expected 'metrics'" % (where, n, stream_type)
                        )

        # The check that catches a layout built for the OLD grid, which the overflow
        # rule cannot: 48 columns fit inside 192 perfectly well, and so does the 174 a
        # hand-dragged dashboard drifts to. What separates a v8 layout from a v5 one is
        # that a v8 layout REACHES the right edge — every reference v7/v8 dashboard
        # ends at exactly 192, every v3/v5 one at exactly 48.
        if placed:
            rightmost = max(l["x"] + l["w"] for _, l in placed)
            if rightmost != GRID_WIDTH:
                problems.append(
                    "%s: widest panel reaches column %d, not %d — a layout that does not reach the "
                    "right edge is built for a different grid, and renders at %d%% width"
                    % (tab, rightmost, GRID_WIDTH, round(100 * rightmost / GRID_WIDTH))
                )

    return problems


def main(argv):
    if len(argv) != 2:
        print("usage: lint_dashboard.py <dashboard.json>", file=sys.stderr)
        return 2
    try:
        with open(argv[1]) as f:
            dash = json.load(f)
    except (OSError, ValueError) as exc:
        print("cannot read %s: %s" % (argv[1], exc), file=sys.stderr)
        return 2

    problems = lint(dash)
    for p in problems:
        print("lint: %s" % p, file=sys.stderr)
    if problems:
        print("\n%d problem(s) — OpenObserve would store this and draw it wrong" % len(problems), file=sys.stderr)
        return 1
    n = sum(len(t.get("panels") or []) for t in dash.get("tabs") or [])
    print("lint: %d panels, all drawable on the %d-column v%d grid" % (n, GRID_WIDTH, SCHEMA_VERSION))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
