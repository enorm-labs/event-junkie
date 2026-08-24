#!/usr/bin/env python3
"""Compare the rules OpenObserve actually holds against the generated `alerts.json`.

**The failure this exists for happened, and nothing noticed for 26 hours** (#702).
`ej-site-down` was rewritten on 2026-08-23 because the `up`-based form fired on
every rolling deploy. The commit landed; the cluster kept the old rule until
`apply.sh` was next run by hand, and in between `alert_history` collected 17
firings of a rule the repository had already replaced.

`check_alerts.py` cannot see that, and reads as though it could. It regenerates
`alerts.json`, evaluates *those* queries against live data, and says `ok` — a
statement about the file, not about the deployment. Throughout those 26 hours it
would have said `ej-site-down ok`, because the query in the file was fine.

    python3 diff_alerts.py "$AUTH" "$SVC" default /tmp/ej-alerts.json

Invoked by `apply.sh --diff`; runs on the node, because OpenObserve has no
ingress route.

## Why this compares a SUBSET rather than the whole object

What comes back is not what was sent. The server fills in defaults
(`ignore_case`, `cron`, `align_time`, `tolerance_in_secs`), stamps identity
(`id`, `owner`, `last_edited_by`) and records state (`last_triggered_at`,
`last_satisfied_at`). Comparing whole objects would report every rule as drifted,
for ever, which is a check nobody keeps running.

So: **every field this repository declares must match; anything the server added
on top is ignored.** That is exactly the property wanted — a change made here and
not applied is caught, and so is an edit made in the UI to a field we own, while
OpenObserve's own bookkeeping is none of our business.

## Two endpoints, because the list is a summary

`GET /api/v2/{org}/alerts` returns rows shaped differently from what is POSTed:
`condition` rather than `query_condition`, and no `destinations`, `stream_name`
or `org_id` at all. Diffing against that shape would silently skip the fields
most worth watching — including the destination, which is where a firing goes.
The list is therefore used only to resolve name -> id, and each rule is then
fetched individually, which returns the POST shape plus the server's additions.

**The template and the destination are deliberately not compared.** The
destination object carries the `Authorization` header `apply_alerts.py` sends,
and a credential that may or may not be echoed back verbatim is a bad thing to
put on either side of a diff. They drift too and that gap is real; it needs a
decision about how to compare a secret-bearing object, not a wider loop here.
"""
import json
import subprocess
import sys

auth, svc, org = sys.argv[1], sys.argv[2], sys.argv[3]
path = sys.argv[4] if len(sys.argv) > 4 else "/tmp/ej-alerts.json"

BASE = "http://%s:5080/api/v2/%s/alerts" % (svc, org)


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


def differences(wanted, actual, path=""):
    """Every place `actual` fails to carry what `wanted` declares, as (path, wanted, actual).

    Recurses into dicts by the keys of `wanted` only — that is what makes this a
    subset comparison. Lists are compared whole: `destinations` is the only one
    here, its order is meaningful to OpenObserve, and an element-wise diff would
    say less than showing both.
    """
    if isinstance(wanted, dict):
        if not isinstance(actual, dict):
            return [(path, wanted, actual)]
        found = []
        for key, value in wanted.items():
            where = "%s.%s" % (path, key) if path else key
            if key not in actual:
                found.append((where, value, "<absent>"))
            else:
                found += differences(value, actual[key], where)
        return found
    if wanted != actual:
        return [(path, wanted, actual)]
    return []


def show(value):
    text = value if isinstance(value, str) else json.dumps(value)
    return text if len(text) <= 200 else text[:197] + "..."


def main():
    with open(path) as f:
        wanted_alerts = {alert["name"]: alert for alert in json.load(f)}

    listing = get(BASE)
    rows = listing.get("list", listing) if isinstance(listing, dict) else listing
    live = {
        row["name"]: (row.get("alert_id") or row.get("id"))
        for row in rows
        if isinstance(row, dict) and "name" in row
    }

    drifted = 0

    for name, wanted in wanted_alerts.items():
        if name not in live:
            print("%-30s MISSING     in alerts.json, absent from the cluster — apply.sh has not been run" % name)
            drifted += 1
            continue

        found = differences(wanted, get("%s/%s" % (BASE, live[name])))
        if not found:
            print("%-30s in sync" % name)
            continue

        drifted += 1
        print("%-30s DIFFERS     %d field%s" % (name, len(found), "" if len(found) == 1 else "s"))
        for where, want, have in found:
            print("    %s" % where)
            print("        alerts.json: %s" % show(want))
            print("        cluster:     %s" % show(have))

    # A rule in the cluster and not in the file is drift too, and is the direction
    # the README already worried about: somebody edits or adds one in the UI, and
    # the next `apply.sh` leaves it standing because it only pushes what it knows.
    for name in sorted(set(live) - set(wanted_alerts)):
        print("%-30s EXTRA       in the cluster, absent from alerts.json — created outside this repository?" % name)
        drifted += 1

    total = len(set(wanted_alerts) | set(live))
    print("\n%d/%d rules match this repository" % (total - drifted, total))
    if drifted:
        print("`./apply.sh` makes the cluster match the file. Check WHY they differ before running it —")
        print("an emergency edit made in the UI is drift that somebody meant.")
    sys.exit(1 if drifted else 0)


main()
