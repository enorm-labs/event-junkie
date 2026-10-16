#!/usr/bin/env python3
"""An apply sends a rule only when the cluster's copy differs from `alerts.json` (#3445).

OpenObserve restarts a rule's silence window on every PUT, so a PUT of an unchanged
rule can mail a firing again inside its silence. `call()` is stubbed with a fake
cluster, so this needs no network.

    python3 deploy/alerts/test_apply_alerts.py        # exits non-zero on failure

`validate-python.yml` runs this beside `test_diff_alerts.py`.
"""

import contextlib
import copy
import io
import json
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import apply_alerts  # noqa: E402  (path set above; this file is run, not imported)

RULES = [
    {
        "name": "ej-one",
        "stream_type": "metrics",
        "stream_name": "up",
        "query_condition": {"promql": "up > 0"},
        "destinations": ["record-only", "email"],
    },
    {
        "name": "ej-two",
        "stream_type": "metrics",
        "stream_name": "kube_pod_info",
        "query_condition": {"promql": "count(kube_pod_info) > 0"},
        "destinations": ["record-only", "email"],
    },
]

failures = []
checks = 0


def check(description, condition):
    global checks
    checks += 1
    print("  %-58s %s" % (description, "ok" if condition else "FAILED"))
    if not condition:
        failures.append(description)


def run(stored_rules):
    """Apply `RULES` against a cluster that holds `stored_rules`. Returns (calls, output)."""
    ids = {rule["name"]: "id-" + rule["name"] for rule in stored_rules}
    by_id = {ids[rule["name"]]: rule for rule in stored_rules}
    calls = []

    def fake_call(method, url, payload=None):
        calls.append((method, url))
        if method == "GET" and url.endswith("/alerts"):
            return 200, json.dumps({"list": [{"name": n, "alert_id": i} for n, i in ids.items()]})
        if method == "GET":
            stored = dict(by_id[url.rsplit("/", 1)[1]])
            # What the server adds on top of what was sent, which the comparison ignores.
            stored.update({"id": url.rsplit("/", 1)[1], "owner": "root@example.org", "last_triggered_at": 1})
            return 200, json.dumps(stored)
        return 200, "{}"

    apply_alerts.call = fake_call
    out = io.StringIO()
    with contextlib.redirect_stdout(out):
        apply_alerts.apply_rules(copy.deepcopy(RULES))
    return calls, out.getvalue()


def writes(calls):
    return [call for call in calls if call[0] != "GET"]


# 1. Every rule matches: no write at all, and each says `unchanged`.
calls, out = run(copy.deepcopy(RULES))
check("matching rules send no PUT, POST or DELETE", writes(calls) == [])
check("each matching rule prints 'unchanged'", all("%-30s unchanged" % r["name"] in out for r in RULES))

# 2. One field differs in one rule: that rule alone is PUT.
drifted = copy.deepcopy(RULES)
drifted[1]["query_condition"]["promql"] = "count(kube_pod_info) > 1"
calls, out = run(drifted)
put = writes(calls)
check(
    "a rule that differs in one field is PUT", len(put) == 1 and put[0][0] == "PUT" and put[0][1].endswith("/id-ej-two")
)
check("the other rule is still left alone", "%-30s unchanged" % "ej-one" in out)

# 3. A changed stream is still a delete and a recreate.
moved = copy.deepcopy(RULES)
moved[0]["stream_name"] = "kube_deployment_status_replicas_available"
calls, out = run(moved)
check("a changed stream is deleted and recreated", [c[0] for c in writes(calls)] == ["DELETE", "POST"])

# 4. A rule absent from the cluster is created.
calls, out = run(RULES[:1])
check("a missing rule is created", [c[0] for c in writes(calls)] == ["POST"] and "ej-two" in out)

print("\n%d checks, %d failed" % (checks, len(failures)))
sys.exit(1 if failures else 0)
