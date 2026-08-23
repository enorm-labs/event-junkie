#!/usr/bin/env python3
"""Run every panel query in a dashboard against the live instance and report which returned rows.

**A dashboard whose panels have never returned a row is a hypothesis, not a dashboard.** Three of
the nine panels here were wrong on the first attempt and every one of them failed silently — a
blank panel and a negative number, not an error. This exists so "it renders" is a checked claim.

Invoked by apply.sh --check; it runs on the node, because OpenObserve has no ingress route.

    python3 check_panels.py "$AUTH" "$SVC" /tmp/ej-dashboard.json
"""
import json
import subprocess
import sys
import time
import urllib.parse

auth, svc = sys.argv[1], sys.argv[2]
path = sys.argv[3] if len(sys.argv) > 3 else "/tmp/ej-dashboard.json"

end = int(time.time())
start = end - 6 * 3600

with open(path) as f:
    dash = json.load(f)
panels = [p for tab in dash["tabs"] for p in tab["panels"]]

failures = 0
for p in panels:
    # Every query, not just the first: a two-query panel whose second query is broken looks fine
    # until you notice one line missing from the chart.
    for n, query in enumerate(p["queries"]):
        q = query["query"]
        label = p["id"] if len(p["queries"]) == 1 else "%s[%d]" % (p["id"], n)
        url = "http://%s:5080/api/default/prometheus/api/v1/query_range?%s" % (
            svc,
            urllib.parse.urlencode({"query": q, "start": start, "end": end, "step": 300}),
        )
        out = subprocess.run(
            ["curl", "-sS", "-m", "60", "-H", "Authorization: " + auth, url],
            capture_output=True, text=True,
        ).stdout
        try:
            d = json.loads(out)
        except ValueError:
            print("%-34s PARSE-FAIL %s" % (label, out[:100]))
            failures += 1
            continue
        if d.get("status") != "success":
            print("%-34s ERROR %s" % (label, str(d.get("error"))[:120]))
            failures += 1
            continue
        r = d["data"]["result"]
        # A spot-check, not a summary: the value is the newest point of the FIRST series only,
        # while series= counts them all. Labelled last[0] so a multi-series panel cannot be read
        # as if one number described every line on the chart. The pass/fail test below is
        # "did any series come back", which is what this script is actually for.
        sample = r[0]["values"][-1][1] if r and r[0].get("values") else None
        print("%-34s series=%-4d last[0]=%-19s%s" % (label, len(r), sample, "" if r else "   <-- NO DATA"))
        if not r:
            failures += 1

total = sum(len(p["queries"]) for p in panels)
print("\n%d/%d queries returned data" % (total - failures, total))
sys.exit(1 if failures else 0)
