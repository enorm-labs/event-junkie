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

## The template and the destination, and the credential in one of them (#704)

Both are compared too, because **the destination is where a firing goes: a wrong
one makes every rule silently undeliverable**, which is worse than a wrong rule.
The UI still shows the alert firing and `last_satisfied_at` still advances while
nobody is told — the combination README.md calls the worst available.

**The header values are compared by fingerprint and can never be printed.** The
destination carries the OpenObserve root credential, and the API hands it straight
back: measured on 2026-08-24, `GET /alerts/destinations/record-only` returns
`{"Authorization": "Basic ..."}` in full, 74 characters, unredacted. A
field-by-field diff of the kind this file does for everything else would put that
credential in a terminal, a scrollback and quite possibly a pasted issue comment.
So both sides are hashed and only the hashes are shown: enough to detect drift,
incapable of disclosing it.

**Every value under `headers` is treated that way, not just `Authorization`** —
header values are where credentials live, and a destination that later carries an
`X-Auth-Token` should not depend on somebody remembering to extend a list.

**If the server ever starts redacting, this says so rather than saying in sync.**
An empty or all-asterisk value is reported as `cannot compare`; any other form of
redaction produces a fingerprint mismatch, which is loud and wrong rather than
quiet and wrong. That asymmetry is deliberate: silence that reads as health is the
failure this whole check exists to remove.
"""
import hashlib
import json
import re
import subprocess
import sys

from alert_objects import DESTINATION_NAME, TEMPLATE_NAME, destination_payload, template_payload

# Tolerant of being imported with no arguments, so the comparison functions can be
# exercised without a cluster — which is how the "a header value is never printed"
# property is tested rather than asserted.
auth, svc, org = (sys.argv + ["", "", ""])[1:4]
path = sys.argv[4] if len(sys.argv) > 4 else "/tmp/ej-alerts.json"

BASE = "http://%s:5080/api/v2/%s/alerts" % (svc, org)
# The template and destination endpoints are v1: there is no v2 for them.
V1 = "http://%s:5080/api/%s/alerts" % (svc, org)

# Anything under this key is a header value, and header values are credentials
# until proven otherwise. Compared by fingerprint, never printed.
SECRET_PARENT = "headers"
REDACTED = re.compile(r"^\**$")


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


def fingerprint(value):
    """A short digest, enough to answer "did this change" and nothing else."""
    return "sha256:" + hashlib.sha256(str(value).encode("utf-8")).hexdigest()[:8]


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
            elif path == SECRET_PARENT or path.endswith("." + SECRET_PARENT):
                # A header value. Hash both sides; the values never leave this frame.
                stored = actual[key]
                if stored is None or (isinstance(stored, str) and REDACTED.match(stored)):
                    found.append((where, fingerprint(value), "cannot compare — the server returned no value"))
                elif fingerprint(value) != fingerprint(stored):
                    found.append((where, fingerprint(value), fingerprint(stored)))
            else:
                found += differences(value, actual[key], where)
        return found
    if wanted != actual:
        return [(path, wanted, actual)]
    return []


def show(value):
    text = value if isinstance(value, str) else json.dumps(value)
    return text if len(text) <= 200 else text[:197] + "..."


def report(label, wanted, stored, missing_note):
    """Compare one named object and print the verdict. Returns 1 if it drifted."""
    if stored is None:
        print("%-30s MISSING     %s" % (label, missing_note))
        return 1

    found = differences(wanted, stored)
    if not found:
        print("%-30s in sync" % label)
        return 0

    print("%-30s DIFFERS     %d field%s" % (label, len(found), "" if len(found) == 1 else "s"))
    for where, want, have in found:
        print("    %s" % where)
        print("        alerts.json: %s" % show(want))
        print("        cluster:     %s" % show(have))
    return 1


def delivery_objects():
    """The template and the destination — what a firing is shaped like, and where it goes.

    Fetched by name rather than listed: both endpoints answer 404 with a body, so
    `None` here means genuinely absent rather than a parse accident.
    """
    template = get("%s/templates/%s" % (V1, TEMPLATE_NAME))
    destination = get("%s/destinations/%s" % (V1, DESTINATION_NAME))
    return (
        template if isinstance(template, dict) and template.get("name") else None,
        destination if isinstance(destination, dict) and destination.get("name") else None,
    )


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

    # The delivery path first, because a rule that matches perfectly still tells
    # nobody anything if these two are wrong, and that is the failure that looks
    # most like health.
    template, destination = delivery_objects()
    drifted += report(
        TEMPLATE_NAME + " (template)",
        template_payload(),
        template,
        "no notification template — firings would arrive shapeless, with no alert name or value",
    )
    drifted += report(
        DESTINATION_NAME + " (destination)",
        destination_payload(org, auth),
        destination,
        "NO DESTINATION — every rule below is undeliverable, and the UI still shows them firing",
    )

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

    total = len(set(wanted_alerts) | set(live)) + 2  # + the template and the destination
    print("\n%d/%d objects match this repository (%d rules, the template and the destination)" % (total - drifted, total, total - 2))
    if drifted:
        print("`./apply.sh` makes the cluster match the file. Check WHY they differ before running it —")
        print("an emergency edit made in the UI is drift that somebody meant.")
    sys.exit(1 if drifted else 0)


if __name__ == "__main__":
    main()
