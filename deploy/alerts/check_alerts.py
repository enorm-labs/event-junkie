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

    url = "http://%s:5080/api/default/prometheus/api/v1/query_range?%s" % (
        svc,
        urllib.parse.urlencode({"query": query, "start": start, "end": end, "step": 60}),
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
    if not values:
        print("%-30s NO DATA   <-- this rule can never fire" % alert["name"])
        failures += 1
        continue

    latest = values[-1]
    crosses = latest > threshold if operator == ">" else latest < threshold
    if crosses:
        firing += 1
    print(
        "%-30s %-10s value=%-12.2f %s %s"
        % (alert["name"], "WOULD FIRE" if crosses else "ok", latest, operator, threshold)
    )

print("\n%d/%d rules return data; %d would fire now" % (len(alerts) - failures, len(alerts), firing))
sys.exit(1 if failures else 0)
