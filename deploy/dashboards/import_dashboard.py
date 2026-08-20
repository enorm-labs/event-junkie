#!/usr/bin/env python3
"""Replace a dashboard in OpenObserve, matching on title.

Runs on the k3s node, because OpenObserve has no ingress route — see apply.sh, which ships this
here and invokes it.

**Replace rather than update, and matched on title rather than ID.** OpenObserve's create endpoint
mints a fresh `dashboardId` on every POST and the repo has no place to record one, so importing
twice would otherwise leave two near-identical dashboards and no way to tell which is current.
Deleting by title first makes the operation idempotent at the cost of a new ID each time, which
nothing depends on.

    python3 import_dashboard.py "$AUTH" "$SVC" default /tmp/ej-dashboard.json
"""
import json
import subprocess
import sys

auth, svc, org, path = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
base = "http://%s:5080/api/%s/dashboards" % (svc, org)


def curl(*args):
    r = subprocess.run(
        ["curl", "-sS", "-m", "60", "-H", "Authorization: " + auth, *args],
        capture_output=True, text=True,
    )
    if r.returncode != 0:
        sys.exit("curl failed: %s" % r.stderr.strip()[:300])
    return r.stdout


def unwrap(entry):
    """A dashboard arrives wrapped as {"v1": null, ..., "v8": {...}}; return the populated one."""
    for key, value in entry.items():
        if key.startswith("v") and isinstance(value, dict) and value.get("dashboardId"):
            return value
    return None


wanted = json.load(open(path))
title = wanted["title"]

listing = json.loads(curl(base)).get("dashboards", [])
for entry in listing:
    existing = unwrap(entry)
    if existing and existing.get("title") == title:
        print("  replacing existing dashboard %s" % existing["dashboardId"])
        curl("-X", "DELETE", "%s/%s" % (base, existing["dashboardId"]), "-o", "/dev/null")

out = curl(
    "-X", "POST", "-H", "Content-Type: application/json",
    "--data-binary", "@" + path, base,
)
try:
    created = unwrap(json.loads(out))
except ValueError:
    sys.exit("create returned non-JSON: %s" % out[:400])

if not created:
    sys.exit("create failed: %s" % out[:400])

panels = sum(len(tab["panels"]) for tab in created["tabs"])
print('  created %s — "%s", %d panels' % (created["dashboardId"], created["title"], panels))
