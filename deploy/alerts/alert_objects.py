#!/usr/bin/env python3
"""The template and the destination this repository declares, defined once.

`apply_alerts.py` pushes these and `diff_alerts.py` compares against them, and
**two copies of the same expected object is precisely the bug this directory has
been fixing all week** (#702, #704): a definition that is edited in one place and
silently not in the other. So the payloads live here and both scripts import them.

Copied to the node next to whichever script needs it — see `apply.sh`.
"""
import json

TEMPLATE_NAME = "event-junkie"
DESTINATION_NAME = "record-only"

def template_body(environment):
    """One line per firing, with the fields an incident actually needs.

    `{alert_name}` and friends are OpenObserve's substitutions, not Python's —
    hence the braces below being single and the strings plain literals.

    **`environment` is ours and is passed in, because OpenObserve has no
    substitution for it** (#928). It was written here as the literal `"staging"`
    when staging was the only cluster running OpenObserve, and #880 made that
    wrong without touching this file: the first alert ever to fire on production
    recorded itself as staging. Both clusters write to a stream of the same name
    in an org of the same name, so this field is the only thing that says which
    one is broken.
    """
    return json.dumps(
        {
            "alert": "{alert_name}",
            "stream": "{stream_name}",
            "org": "{org_name}",
            "value": "{value}",
            # No `{timestamp}`: it is not a substitution OpenObserve knows, so it
            # arrived as the literal string "{timestamp}" in every row. The ingest
            # timestamp is already on the row as `_timestamp`, which is the one a
            # query would use anyway. Verified substitutions: `{alert_name}`,
            # `{stream_name}`, `{org_name}`, `{value}`.
            "environment": environment,
        }
    )


def template_payload(environment):
    """The notification body. No secret in it, so it is compared by value."""
    return {"name": TEMPLATE_NAME, "body": template_body(environment), "type": "http"}


def destination_payload(org, auth):
    """Where a firing goes.

    **`auth` is the OpenObserve root credential**, passed through from the caller
    so that it is never written down in git. It is the reason `diff_alerts.py`
    compares the `headers` map by fingerprint and can never print it.
    """
    return {
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
