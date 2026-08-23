#!/usr/bin/env python3
"""Run every alert's PromQL against the live instance and say whether it would fire.

**An alert rule that has never been evaluated is a hypothesis**, and the failure
mode is the quiet one: a query that returns no series never fires, looks
identical to a healthy system, and is only discovered during the incident it was
written for. `../dashboards/check_panels.py` exists for the same reason and found
three wrong panels out of nine on the first attempt.

This reports three things per rule, and they are different questions:

    NO DATA     the query returns nothing — the rule can never fire
    would fire  the query returns a value that crosses the threshold NOW
    ok          returns data, below the threshold

`would fire` is not necessarily wrong. On staging today `ej-importer-stale` is
one of them, because a source really has not succeeded in 70 hours.

Invoked by apply.sh --check; it runs on the node, because OpenObserve has no
ingress route.

    python3 check_alerts.py "$AUTH" "$SVC" /tmp/ej-alerts.json
"""
import json
import subprocess
import sys
import time
import urllib.parse

auth, svc = sys.argv[1], sys.argv[2]
path = sys.argv[3] if len(sys.argv) > 3 else "/tmp/ej-alerts.json"

with open(path) as f:
    alerts = json.load(f)

failures = 0
firing = 0

for alert in alerts:
    cond = alert["query_condition"]
    query = cond["promql"]
    threshold = cond["promql_condition"]["value"]
    operator = cond["promql_condition"]["operator"]

    # The rule's own window, so what is checked is what will be evaluated rather
    # than a convenient six hours.
    end = int(time.time())
    start = end - alert["trigger_condition"]["period"] * 60

    # **The alert does not run the bare expression** — `core/src/alerts/mod.rs`
    # rewrites it as `({promql}) {operator} {value}` and evaluates that, so a
    # match is "this returned rows" rather than "this returned a number I then
    # compared". Checking the bare query would validate something the scheduler
    # never runs, which is how the first version of these rules passed a check
    # and then fired on nothing.
    evaluated = "(%s) %s %s" % (query, "==" if operator == "=" else operator, threshold)
    url = "http://%s:5080/api/default/prometheus/api/v1/query_range?%s" % (
        svc,
        urllib.parse.urlencode({"query": evaluated, "start": start, "end": end, "step": 60}),
    )
    out = subprocess.run(
        ["curl", "-sS", "-m", "60", "-H", "Authorization: " + auth, url],
        capture_output=True,
        text=True,
    ).stdout

    try:
        body = json.loads(out)
    except ValueError:
        print("%-30s PARSE-FAIL %s" % (alert["name"], out[:80]))
        failures += 1
        continue

    if body.get("status") != "success":
        print("%-30s ERROR %s" % (alert["name"], str(body.get("error"))[:100]))
        failures += 1
        continue

    values = [float(v[1]) for series in body["data"]["result"] for v in series.get("values", [])]

    # Rows back from the rewritten expression IS the firing condition. An empty
    # result is the healthy case here, so it is reported as `ok` rather than as
    # NO DATA — the un-fireable case is caught by the bare-query probe below.
    if values:
        firing += 1
        print("%-30s WOULD FIRE  value=%-12.2f %s %s" % (alert["name"], values[-1], operator, threshold))
        continue

    # The rule is quiet. Distinguish "quiet because the system is healthy" from
    # "quiet because the query matches nothing and never will", which look
    # identical from the alert's side and are the failure this whole file exists
    # to catch.
    bare = "http://%s:5080/api/default/prometheus/api/v1/query_range?%s" % (
        svc,
        urllib.parse.urlencode({"query": query, "start": start, "end": end, "step": 60}),
    )
    probe = subprocess.run(
        ["curl", "-sS", "-m", "60", "-H", "Authorization: " + auth, bare],
        capture_output=True,
        text=True,
    ).stdout
    try:
        has_series = bool(json.loads(probe)["data"]["result"])
    except (ValueError, KeyError):
        has_series = False
    if not has_series:
        print("%-30s NO DATA     <-- the expression matches nothing; this rule can never fire" % alert["name"])
        failures += 1
    else:
        print("%-30s ok          below %s %s" % (alert["name"], operator, threshold))

print("\n%d/%d rules return data; %d would fire now" % (len(alerts) - failures, len(alerts), firing))
sys.exit(1 if failures else 0)
