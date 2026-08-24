#!/usr/bin/env python3
"""Compare the dashboard OpenObserve actually holds against the JSON next to this script.

The same gap `../alerts/diff_alerts.py` closes, on the same seam (#702): these are
API objects, Flux cannot reconcile them, and until now nothing could answer "is
what is running what this repository says". `--check` validates that the panel
queries return data — a statement about the file, not about the deployment.

    python3 diff_dashboard.py "$AUTH" "$SVC" default /tmp/ej-dashboard.json

Invoked by `apply.sh --diff`; runs on the node, because OpenObserve has no ingress
route.

**A subset comparison, for the reason the alerts one gives at length**: the stored
object carries fields this repository does not set. Every field the file *does*
declare must match; anything the server added is ignored.

**Two kinds of noise had to be silenced before the output was worth reading**, and
both are the file's own placeholders rather than the server's doing.
`is-it-healthy.json` declares `dashboardId: ""`, `owner: ""`, `role: ""`,
`updatedAt: 0` and a fixed `created` — scaffolding the import API fills in, so
comparing them reports drift on every run for ever. And it declares `null` in
several places (`variables`, `defaultDatetimeDuration.startTime`) where the server
simply omits the key; a null in the file is not an assertion that the field is
absent. Both are skipped, which leaves the output saying only what changed about
the dashboard itself.

`differences()` is duplicated from `../alerts/diff_alerts.py` rather than shared.
Each of these scripts is copied to the node on its own and has to run there with
nothing beside it, which is what makes a shared module the more expensive option
for fifteen lines. If a third one appears, that trade changes.
"""
import json
import subprocess
import sys

auth, svc, org = sys.argv[1], sys.argv[2], sys.argv[3]
path = sys.argv[4] if len(sys.argv) > 4 else "/tmp/ej-dashboard.json"

BASE = "http://%s:5080/api/%s/dashboards" % (svc, org)


def get(url):
    out = subprocess.run(
        ["curl", "-sS", "-m", "60", "-H", "Authorization: " + auth, url],
        capture_output=True,
        text=True,
    ).stdout
    try:
        return json.loads(out)
    except ValueError:
        sys.exit("not JSON from %s: %s" % (url, out[:200]))


def unwrap(entry):
    """A dashboard arrives wrapped as {"v1": null, ..., "v8": {...}}; return the populated one."""
    for key, value in entry.items():
        if key.startswith("v") and isinstance(value, dict) and value.get("dashboardId"):
            return value
    return None


# Set by the import API, not by this repository: the file carries empty placeholders
# for all of them, so comparing them would report drift on every single run.
SERVER_OWNED = {"dashboardId", "owner", "role", "created", "updatedAt"}


def differences(wanted, actual, where=""):
    """Every place `actual` fails to carry what `wanted` declares, as (path, wanted, actual)."""
    # A null in the file is a placeholder, not a claim that the key is absent.
    if wanted is None:
        return []
    if isinstance(wanted, dict):
        if not isinstance(actual, dict):
            return [(where, wanted, actual)]
        found = []
        for key, value in wanted.items():
            if not where and key in SERVER_OWNED:
                continue
            path = "%s.%s" % (where, key) if where else key
            if key not in actual:
                # A null the server omits entirely is the same non-statement as a null
                # it stores, so it is not drift either. Checked here as well as on the
                # way in, because this branch never recurses.
                if value is not None:
                    found.append((path, value, "<absent>"))
            else:
                found += differences(value, actual[key], path)
        return found
    if isinstance(wanted, list):
        if not isinstance(actual, list) or len(wanted) != len(actual):
            return [(where, "%d entries" % len(wanted), "%d entries" % (len(actual) if isinstance(actual, list) else -1))]
        found = []
        for index, value in enumerate(wanted):
            found += differences(value, actual[index], "%s[%d]" % (where, index))
        return found
    if wanted != actual:
        return [(where, wanted, actual)]
    return []


def show(value):
    text = value if isinstance(value, str) else json.dumps(value)
    return text if len(text) <= 200 else text[:197] + "..."


def main():
    with open(path) as f:
        wanted = json.load(f)
    title = wanted["title"]

    live = None
    for entry in get(BASE).get("dashboards", []):
        candidate = unwrap(entry)
        if candidate and candidate.get("title") == title:
            live = candidate
            break

    if live is None:
        print('"%s" is not in the cluster at all — apply.sh has not been run' % title)
        sys.exit(1)

    # The listing is the full object here, unlike the alerts API, so no second fetch.
    found = differences(wanted, live)
    if not found:
        panels = sum(len(tab["panels"]) for tab in live["tabs"])
        print('"%s" matches this repository (%d panels, dashboardId %s)' % (title, panels, live["dashboardId"]))
        sys.exit(0)

    print('"%s" DIFFERS from this repository — %d field%s' % (title, len(found), "" if len(found) == 1 else "s"))
    for where, want, have in found:
        print("    %s" % where)
        print("        file:    %s" % show(want))
        print("        cluster: %s" % show(have))
    print("\n`./apply.sh` replaces the cluster's copy with this file. Check WHY they differ first —")
    print("a panel someone fixed in the UI is drift that somebody meant, and re-importing discards it.")
    sys.exit(1)


main()
