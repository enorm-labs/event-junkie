#!/usr/bin/env python3
"""Set, or check, each stream's settings from streams.json.

Runs on the k3s node, because OpenObserve has no ingress route; apply.sh ships this and invokes it.

    python3 apply_streams.py "$AUTH" "$SVC" default /tmp/ej-streams.json apply|check
"""

import argparse
import json
import subprocess
import sys
import time


def curl(auth, *args):
    r = subprocess.run(
        ["curl", "-sS", "-m", "60", "-H", "Authorization: " + auth, *args],
        capture_output=True,
        text=True,
    )
    if r.returncode != 0:
        sys.exit("curl failed: %s" % r.stderr.strip()[:300])
    return r.stdout


def current(auth, base, name, kind):
    """The stream's settings, or None when OpenObserve has no such stream yet."""
    listing = json.loads(curl(auth, "%s?type=%s" % (base, kind))).get("list", [])
    for stream in listing:
        if stream.get("name") == name:
            return stream.get("settings", {})
    return None


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("auth")
    parser.add_argument("svc")
    parser.add_argument("org")
    parser.add_argument("path")
    parser.add_argument("mode", choices=["apply", "check"])
    args = parser.parse_args()

    base = "http://%s:5080/api/%s/streams" % (args.svc, args.org)
    with open(args.path) as f:
        wanted = json.load(f)

    status = 0
    for name, spec in wanted.items():
        kind = spec["type"]
        days = spec["data_retention"]
        have = current(args.auth, base, name, kind)
        if have is None:
            # Creating it here would make an empty stream the collector then writes into by name, which
            # works, but hides a collector that never sends: the missing stream is the signal.
            print("  %s: NO STREAM - nothing has written to it yet" % name)
            status = 1
            continue
        if have.get("data_retention") == days:
            print("  %s: ok, data_retention %d" % (name, days))
            continue
        if args.mode == "check":
            print("  %s: DIFFERS, data_retention %s, want %d" % (name, have.get("data_retention"), days))
            status = 1
            continue
        out = curl(
            args.auth,
            "-X",
            "PUT",
            "-H",
            "Content-Type: application/json",
            "--data-binary",
            json.dumps({"data_retention": days}),
            "%s/%s/settings?type=%s" % (base, name, kind),
        )
        # The stream listing is cached and lags the write by a few seconds.
        for _ in range(10):
            after = current(args.auth, base, name, kind) or {}
            if after.get("data_retention") == days:
                break
            time.sleep(1)
        if after.get("data_retention") != days:
            print("  %s: FAILED, the server answered %s" % (name, out.strip()[:200]))
            status = 1
            continue
        print("  %s: set data_retention %d (was %s)" % (name, days, have.get("data_retention")))
    sys.exit(status)


if __name__ == "__main__":
    main()
