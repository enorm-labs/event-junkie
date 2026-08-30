#!/usr/bin/env python3
"""Push the alert template, destination and rules into OpenObserve.

Runs on the node (see apply.sh). Idempotent: every object is matched by name, and
an existing one is updated in place rather than duplicated — the same property
`../dashboards/import_dashboard.py` has, and for the same reason. Re-running this
after editing `gen_alerts.py` is the normal workflow.

    python3 apply_alerts.py "$AUTH" "$SVC" default /tmp/ej-alerts.json

## What it creates, in dependency order

    1. a template    `event-junkie`   — the body of a notification
    2. a destination `record-only`    — where a firing goes
    3. the rules themselves

**The destination posts back into OpenObserve**, as JSON into an `alert_history`
stream. A firing therefore becomes a queryable row rather than a message nobody
receives, which is what makes these rules exercised rather than hypothetical
while #271 item 4 waits on a phone number.

**Switching to the Signal bridge now needs only a registered number.** It once
also needed a way past OpenObserve's SSRF guard, which rejected any destination
resolving inside the cluster:

    signal-cli.observability.svc.cluster.local
      -> 400 Destination URL blocked by SSRF guard

That is gone. `ZO_SKIP_SSRF_CHECKS` is set in the HelmRelease, and the control
moved to the network: `observability-netpol.yaml` permits this pod to reach
CoreDNS, the internet on 443 and the Signal bridge, and nothing else. So the
remaining work for #271 item 4 is to point DESTINATION_NAME at
`http://signal-cli.observability.svc.cluster.local:8080/v2/send` once the
number exists.
"""
import json
import subprocess
import sys

from alert_objects import DESTINATION_NAME, TEMPLATE_NAME, destination_payload, template_payload

auth, svc, org, path = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
base = "http://%s:5080/api/%s" % (svc, org)


def call(method, url, payload=None):
    cmd = ["curl", "-sS", "-m", "60", "-X", method, "-H", "Authorization: " + auth]
    if payload is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(payload)]
    cmd += ["-w", "\n%{http_code}", url]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    body, _, code = out.rpartition("\n")
    return int(code or 0), body


def ensure_template():
    code, _ = call("POST", base + "/alerts/templates", template_payload())
    if code in (409, 400):  # already exists — update it, so an edit here lands
        code, body = call("PUT", "%s/alerts/templates/%s" % (base, TEMPLATE_NAME), template_payload())
        print("template %s updated (%s)" % (TEMPLATE_NAME, code))
    else:
        print("template %s created (%s)" % (TEMPLATE_NAME, code))


def ensure_destination():
    payload = destination_payload(org, auth)
    code, _ = call("POST", base + "/alerts/destinations", payload)
    if code in (409, 400):
        code, _ = call("PUT", "%s/alerts/destinations/%s" % (base, DESTINATION_NAME), payload)
        print("destination %s updated (%s)" % (DESTINATION_NAME, code))
    else:
        print("destination %s created (%s)" % (DESTINATION_NAME, code))


def existing_alerts():
    code, body = call("GET", "http://%s:5080/api/v2/%s/alerts" % (svc, org))
    if code != 200:
        return {}
    try:
        listing = json.loads(body)
    except ValueError:
        return {}
    rows = listing.get("list", listing) if isinstance(listing, dict) else listing
    return {row["name"]: row.get("alert_id") or row.get("id") for row in rows if isinstance(row, dict) and "name" in row}


def stored_stream(alert_id):
    """The (stream_type, stream_name) an existing alert is bound to, or None if it cannot be read."""
    code, body = call("GET", "http://%s:5080/api/v2/%s/alerts/%s" % (svc, org, alert_id))
    if code != 200:
        return None
    try:
        stored = json.loads(body)
    except ValueError:
        return None
    return stored.get("stream_type"), stored.get("stream_name")


def main():
    ensure_template()
    ensure_destination()

    known = existing_alerts()
    with open(path) as f:
        alerts = json.load(f)

    for alert in alerts:
        name = alert["name"]
        if name in known and known[name]:
            # **A PUT cannot move an alert to a different stream, and says 200 anyway.**
            # Measured with `ej-site-down` (#702): rewriting its query from `up` to
            # `kube_deployment_status_replicas_available` reported success, the query
            # changed and `stream_name` did not. Nothing errored, and the only
            # visible symptom was `alert_history` rows labelled with the old stream —
            # which is worse than an error, because a diagnostic that reads that label
            # then dates a firing to a rule that is no longer installed.
            #
            # So a stream change is a delete and a recreate. The cost is real and is
            # accepted here: the alert loses its `last_triggered_at` and its silence
            # window, so a condition that is true right now can fire again immediately.
            # The alternative is a rule whose published identity disagrees with its
            # query, permanently, with no way to correct it short of the UI.
            if stored_stream(known[name]) not in (None, (alert.get("stream_type"), alert.get("stream_name"))):
                call("DELETE", "http://%s:5080/api/v2/%s/alerts/%s" % (svc, org, known[name]))
                code, body = call("POST", "http://%s:5080/api/v2/%s/alerts" % (svc, org), alert)
                verb = "recreated"
            else:
                code, body = call("PUT", "http://%s:5080/api/v2/%s/alerts/%s" % (svc, org, known[name]), alert)
                verb = "updated"
        else:
            code, body = call("POST", "http://%s:5080/api/v2/%s/alerts" % (svc, org), alert)
            verb = "created"
        ok = 200 <= code < 300
        print("%-30s %-8s %s%s" % (name, verb, code, "" if ok else "  <-- " + body[:160]))
        if not ok:
            sys.exit(1)


main()
