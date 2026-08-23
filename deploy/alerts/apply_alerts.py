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

**It cannot yet post to the Signal bridge, and not because of the phone number.**
OpenObserve refuses any destination whose URL resolves inside the cluster:

    signal-cli.observability.svc.cluster.local
      -> 400 Destination URL blocked by SSRF guard

That is #271 item 4's whole architecture, blocked by a control that has nothing
to do with registration. Loopback is permitted (`ZO_SSRF_ALLOW_LOOPBACK`, set in
the HelmRelease) and is what this destination uses; reaching another pod needs
`ZO_SKIP_SSRF_CHECKS`, which removes the check for every destination in a
namespace that currently has no NetworkPolicies. See README.md — that is a
decision to take with the Signal route, not a detail of this script.
"""
import json
import subprocess
import sys

auth, svc, org, path = sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4]
base = "http://%s:5080/api/%s" % (svc, org)

TEMPLATE_NAME = "event-junkie"
DESTINATION_NAME = "record-only"

# One line per firing, with the fields an incident actually needs. `{alert_name}`
# and friends are OpenObserve's substitutions, not Python's — hence the doubled
# braces below being absent and the string being a plain literal.
TEMPLATE_BODY = json.dumps(
    {
        "alert": "{alert_name}",
        "stream": "{stream_name}",
        "org": "{org_name}",
        "value": "{value}",
        "fired_at": "{timestamp}",
        "environment": "staging",
    }
)


def call(method, url, payload=None):
    cmd = ["curl", "-sS", "-m", "60", "-X", method, "-H", "Authorization: " + auth]
    if payload is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(payload)]
    cmd += ["-w", "\n%{http_code}", url]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    body, _, code = out.rpartition("\n")
    return int(code or 0), body


def ensure_template():
    code, _ = call("POST", base + "/alerts/templates", {"name": TEMPLATE_NAME, "body": TEMPLATE_BODY, "type": "http"})
    if code in (409, 400):  # already exists — update it, so an edit here lands
        code, body = call("PUT", "%s/alerts/templates/%s" % (base, TEMPLATE_NAME), {"name": TEMPLATE_NAME, "body": TEMPLATE_BODY, "type": "http"})
        print("template %s updated (%s)" % (TEMPLATE_NAME, code))
    else:
        print("template %s created (%s)" % (TEMPLATE_NAME, code))


def ensure_destination():
    # Posting to OpenObserve's own ingest API. The Authorization header is passed
    # through from the caller rather than written down anywhere: this file is in
    # git and the credential is not.
    payload = {
        "name": DESTINATION_NAME,
        "type": "http",
        # **Loopback, not the service DNS name**, and not for tidiness: OpenObserve's SSRF
        # guard refuses any destination pointing at an internal domain, including its own
        # Service. `ZO_SSRF_ALLOW_LOOPBACK` (set in the HelmRelease) permits exactly this
        # one case — the process notifying itself — and nothing wider. Single-node local
        # mode means 127.0.0.1 is this pod, which is where the ingest API lives.
        "url": "http://127.0.0.1:5080/api/%s/alert_history/_json" % org,
        "method": "post",
        "skip_tls_verify": False,
        "template": TEMPLATE_NAME,
        "headers": {"Authorization": auth},
    }
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


def main():
    ensure_template()
    ensure_destination()

    known = existing_alerts()
    for alert in json.load(open(path)):
        name = alert["name"]
        if name in known and known[name]:
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
