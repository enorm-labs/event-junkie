#!/usr/bin/env python3
"""Demonstrate that `lint_dashboard.py` rejects what it claims to reject.

A linter nobody has watched fail is not evidence. Every rule here exists because
something silently rendered wrong (#969), and the point of the file is that each
rule is shown catching its own case rather than asserted to.

The two that matter most cannot be reached from the real dashboard, because the
real dashboard is correct: overlapping panels, and a layout that overflows the
grid. Both are fabricated here.

    python3 deploy/dashboards/test_lint_dashboard.py    # exits non-zero on failure

Nothing runs this automatically — there is no Python suite in this repository and
two files do not justify inventing one. Run it after touching `lint()`.
"""
import copy
import json
import pathlib
import sys

HERE = pathlib.Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import lint_dashboard  # noqa: E402  (path set above; this file is run, not imported)

failures = []
checks = 0


def check(description, condition):
    global checks
    checks += 1
    print("  %-62s %s" % (description, "ok" if condition else "FAILED"))
    if not condition:
        failures.append(description)


def query(stream_type="metrics", q="up"):
    return {"query": q, "customQuery": True, "fields": {"stream": "", "stream_type": stream_type}}


def dashboard(*panels):
    """A schema-valid dashboard wrapping the panels given."""
    return {"version": 8, "tabs": [{"tabId": "default", "name": "Overview", "panels": list(panels)}]}


def tabbed(**tabs):
    """A dashboard of several named tabs, for the rules that are scoped per tab."""
    return {
        "version": 8,
        "tabs": [{"tabId": n, "name": n, "panels": list(ps)} for n, ps in tabs.items()],
    }


def a_panel(pid="p", typ="line", x=0, y=0, w=192, h=10, queries=None, query_type="promql"):
    return {
        "id": pid, "type": typ, "title": pid, "queryType": query_type,
        "queries": [query()] if queries is None else queries,
        "layout": {"x": x, "y": y, "w": w, "h": h, "i": 1},
    }


def problems(dash):
    return lint_dashboard.lint(dash)


print("the dashboard this repository ships")
shipped = json.loads((HERE / "is-it-healthy.json").read_text())
check("is-it-healthy.json passes with no problems", problems(shipped) == [])
internals = json.loads((HERE / "openobserve-internals.json").read_text())
check("openobserve-internals.json passes with no problems", problems(internals) == [])
check("...and it is the multi-tab case the per-tab rules exist for", len(internals["tabs"]) > 1)

print("\npanel type")
stat = problems(dashboard(a_panel(typ="stat")))
check("'stat' is rejected", any("'stat'" in p for p in stat))
check("...and the message names 'metric' as the type to use", any("metric" in p for p in stat))
check("'metric' is accepted", problems(dashboard(a_panel(typ="metric"))) == [])
check("an invented type is rejected", any("not one OpenObserve draws" in p for p in problems(dashboard(a_panel(typ="singlestat")))))

print("\nthe grid")
check("a full-width layout is accepted", problems(dashboard(a_panel(w=192))) == [])
narrow = problems(dashboard(a_panel(w=48)))
check("a 48-column layout is rejected", any("different grid" in p for p in narrow))
check("...and the message says how much of the screen it uses", any("25% width" in p for p in narrow))
over = problems(dashboard(a_panel(x=160, w=48)))
check("a panel past the right edge is rejected", any("past the 192-column grid" in p for p in over))
check("a negative coordinate is rejected", any("negative" in p for p in problems(dashboard(a_panel(x=-1, w=193)))))
check("a zero-width panel is rejected", any("must be positive" in p for p in problems(dashboard(a_panel(w=0)))))

print("\noverlap")
side_by_side = dashboard(a_panel(pid="l", x=0, w=96), a_panel(pid="r", x=96, w=96))
check("panels that merely touch do not overlap", problems(side_by_side) == [])
stacked = dashboard(a_panel(pid="top", x=0, y=0, w=192, h=10), a_panel(pid="bottom", x=0, y=10, w=192, h=10))
check("panels stacked vertically do not overlap", problems(stacked) == [])
check("two panels in the same place are rejected",
      any("overlaps" in p for p in problems(dashboard(a_panel(pid="a"), a_panel(pid="b")))))
check("a partial overlap is rejected",
      any("overlaps" in p for p in problems(dashboard(a_panel(pid="a", x=0, y=0, w=192, h=10),
                                                      a_panel(pid="b", x=0, y=5, w=192, h=10)))))

print("\ntabs are separate grids")
# Every tab starts at y=0 and shares coordinates with the others. Checking overlap across a whole
# dashboard reported 116 findings on the first eight-tab dashboard, none of them real (#971).
same_place = tabbed(one=[a_panel(pid="a")], two=[a_panel(pid="b")])
check("identical coordinates in different tabs do not overlap", problems(same_place) == [])
check("overlap within one tab is still caught",
      any("overlaps" in p for p in problems(tabbed(one=[a_panel(pid="a"), a_panel(pid="b")], two=[a_panel(pid="c")]))))
short = tabbed(wide=[a_panel(pid="a", w=192)], narrow=[a_panel(pid="b", w=96)])
check("a tab that does not reach the right edge is caught", any("different grid" in p for p in problems(short)))
check("...and the message names which tab", any(p.startswith("narrow:") for p in problems(short)))
check("every tab reaching the edge passes",
      problems(tabbed(one=[a_panel(pid="a")], two=[a_panel(pid="b", x=0, w=96), a_panel(pid="c", x=96, w=96)])) == [])
check("a duplicate id across tabs is still caught",
      any("duplicate panel id" in p for p in problems(tabbed(one=[a_panel(pid="same")], two=[a_panel(pid="same")]))))

print("\nqueries")
check("a panel with no queries is rejected", any("no queries" in p for p in problems(dashboard(a_panel(queries=[])))))
check("an empty query string is rejected",
      any("is empty" in p for p in problems(dashboard(a_panel(queries=[query(q="   ")])))))
check("a promql query on a logs stream is rejected",
      any("expected 'metrics'" in p for p in problems(dashboard(a_panel(queries=[query(stream_type="logs")])))))
check("a markdown panel needs no query", problems(dashboard(a_panel(typ="markdown", queries=[]))) == [])

print("\nthe dashboard as a whole")
check("a wrong schema version is rejected", any("version" in p for p in problems({"version": 5, "tabs": []})))
check("a dashboard with no panels is rejected", any("no panels" in p for p in problems(dashboard())))
dup = dashboard(a_panel(pid="same", x=0, w=96), a_panel(pid="same", x=96, w=96))
check("a duplicate panel id is rejected", any("duplicate panel id" in p for p in problems(dup)))

print("\nthe regression this was written for")
was_broken = copy.deepcopy(shipped)
for p in was_broken["tabs"][0]["panels"]:
    if p["type"] == "metric":
        p["type"] = "stat"
    for k in ("x", "w"):
        p["layout"][k] //= 4
check("the shape #969 filed is caught", len(problems(was_broken)) == 6)

print("\n%d checks, %d failed" % (checks, len(failures)))
sys.exit(1 if failures else 0)
