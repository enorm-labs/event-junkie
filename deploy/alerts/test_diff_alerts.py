#!/usr/bin/env python3
"""The one property in this directory that must not be argued, only demonstrated:

**a header value is never printed, not even when it differs.**

`diff_alerts.py` prints a field-by-field diff, and the destination it compares
carries the OpenObserve root credential — which the API hands back verbatim
(measured 2026-08-24: 74 characters, unredacted). Everything else here is checked
by running it against the cluster; this cannot be, because the failure it guards
against is a mismatch, and a mismatch on staging means the alerting is broken.
So the comparison is exercised directly, with fabricated credentials, no network.

    python3 deploy/alerts/test_diff_alerts.py        # exits non-zero on failure

Nothing runs this automatically — there is no Python suite in this repository and
one file does not justify inventing one. Run it after touching `differences()`.
"""
import pathlib
import sys

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))

import diff_alerts  # noqa: E402  (path set above; this file is run, not imported)
from alert_objects import destination_payload  # noqa: E402

SECRET = "Basic cm9vdEBleGFtcGxlLmRlOnN1cGVyLXNlY3JldC1wYXNzd29yZA=="
OTHER = "Basic cm9vdEBleGFtcGxlLmRlOnNvbWV0aGluZy1lbHNl"

failures = []
checks = 0


def check(description, condition):
    global checks
    checks += 1
    print("  %-58s %s" % (description, "ok" if condition else "FAILED"))
    if not condition:
        failures.append(description)


wanted = destination_payload("default", SECRET)

# 1. Two different credentials: reported, and reported as hashes.
found = diff_alerts.differences(wanted, destination_payload("default", OTHER))
printed = " ".join(str(part) for row in found for part in row)
check("a changed header is reported", len(found) == 1 and found[0][0] == "headers.Authorization")
check("both sides are shown as digests", all(str(v).startswith("sha256:") for v in found[0][1:]))
check(
    "neither credential appears anywhere in the output",
    not any(leak in printed for leak in (SECRET, OTHER, SECRET.split()[1][:12], OTHER.split()[1][:12])),
)

# 2. The same credential is not drift.
check("an unchanged header is silent", diff_alerts.differences(wanted, destination_payload("default", SECRET)) == [])

# 3. A redacted value must say so rather than claim agreement — silence that reads
#    as health is the failure this whole check exists to remove.
redacted = destination_payload("default", SECRET)
redacted["headers"]["Authorization"] = "****"
found = diff_alerts.differences(wanted, redacted)
check("a redacted value says 'cannot compare'", len(found) == 1 and "cannot compare" in str(found[0][2]))

# 4. Fingerprinting is scoped to headers: everything else still shows its value,
#    which is what makes the diff useful at all.
changed = destination_payload("default", SECRET)
changed["url"] = "http://elsewhere.example/hook"
check("a changed url is still shown in full", diff_alerts.differences(wanted, changed)[0][2] == changed["url"])

print("\n%d checks, %d failed" % (checks, len(failures)))
sys.exit(1 if failures else 0)
