#!/usr/bin/env bash
#
# Can each environment's hardware actually be bought right now?
#
#   cd infra && ./check-capacity.sh              # every environment, from the advertisement
#   cd infra && ./check-capacity.sh staging      # one environment
#   cd infra && ./check-capacity.sh --all        # inventory: everything advertised in eu-central
#   cd infra && ./check-capacity.sh --probe      # ORDERS one bare server per type and deletes it
#   cd infra && ./check-capacity.sh --probe production
#
# THE DEFAULT MODE ANSWERS A WEAKER QUESTION THAN IT LOOKS LIKE, AND IT HAS BEEN WRONG IN BOTH
# DIRECTIONS. It reports what Hetzner *advertises* in the `datacenters` endpoint. That is not what
# an order will do, there is no dry-run for a server order, and the two have now disagreed three
# times out of four — twice saying yes to something unbuyable, once saying no to something buyable:
#
#   2026-08-11  The whole CAX (ARM) line was unavailable across eu-central and fsn1 had nothing at
#               all. The apply failed with `error during placement (resource_unavailable)`. Here the
#               advertisement and the order path agreed.
#
#   2026-08-13  Advertised cax11 as available in nbg1; cax11's own pricing lists nbg1. Three orders
#               were refused with `unsupported location for server type (invalid_input)` — a
#               *different* error from a sold-out one, which is why it reads like a configuration
#               fault rather than a capacity one. It is not. Settled by ordering a bare cax11
#               through the API — no Primary IPs, no network, no firewall — and getting the same
#               refusal, so it is Hetzner's side and not our configuration.
#
#   2026-08-20  Omitted cx33 from nbg1 entirely. The order succeeded. Staging is declared cx33
#               today because of that order and not because of this script.
#
#   2026-08-21  Advertised cax21 and cax11 in nbg1 and hel1. Bare orders were refused in nbg1, hel1
#               *and* fsn1 — ARM cannot be bought anywhere in eu-central. Production moved to x86
#               (cx33 + cx23, both ordered successfully in fsn1) as a result. Acting on the green
#               result would have moved production to nbg1 to collect exactly the same refusal.
#
# So: **`--probe` is the mode that answers the question.** It places a real order per type — no
# IPs, no network, `start_after_create: false` — and deletes anything that succeeds, verifying the
# deletion rather than trusting the status code. A refusal costs nothing and returns in about a
# tenth of a second; a success is billed by the hour and lives for seconds.
#
# That is also the loop to wait on, and the reason this line changed: the advertisement-based one
# that used to be here would not have gone green for cx33 on 2026-08-20, when cx33 was orderable.
#
#   until ./check-capacity.sh --probe production; do sleep 1800; done && say "production can go ARM"
#
# The two refusal codes do not mean the same thing. `resource_unavailable` means the type is sold
# here and merely out of stock, so it can come back. `unsupported location` has not been seen to
# resolve — but it is also the code ARM has been refused with everywhere for ten days, so read it as
# "not yet seen to resolve" rather than as a promise that it never will.
#
# For the shape of a shortage over time — flickering hourly, or gone for a week — Server Radar polls
# every minute and keeps the history:
#
#   https://radar.iodev.org/cloud-status?arch=arm
#
# Community-run (github.com/elsbrock/hetzner-radar), not Hetzner. It is the trend; the default mode
# here is the current advertisement; only `--probe` is the order path.

set -euo pipefail

readonly API=https://api.hetzner.cloud/v1

# What each environment REQUIRES, as `<name>=<location>:<type>[,<type>…]`. These decide the exit
# code.
#
# KEEP IN STEP WITH infra/environments/*/main.tf — the `location` and `*_server_type` values there
# are the truth and this is a copy. They diverged once already: staging moved to nbg1 on 2026-08-13
# while this script still called fsn1 "the preferred location" for everything, so its output
# answered a question nobody was asking.
export ENVIRONMENTS="staging=nbg1:cx33;production=fsn1:cx33,cx23"

# Types worth being told about but which nothing depends on: what each environment would move to if
# capacity returned. Reported, never counted — a watch entry that turns the script red makes the
# `until` loop above permanently false, which is exactly what the old `cx43` entry did.
#
#   staging     cx43 is 16 GB for €19.03 and was `resource_unavailable` in nbg1 on 2026-08-21 —
#               supported there, out of stock, so it can return. It is orderable in fsn1, which does
#               staging no good: the Primary IPs and the PGDATA volume are location-bound (#460).
#   production  the ARM pair it was declared as until 2026-08-21, and would go back to if that were
#               both buyable and still cheaper. Today it is neither.
export WATCH="staging=nbg1:cx43;production=fsn1:cax21,cax11"

export NETWORK_ZONE=eu-central

if [ -z "${HCLOUD_TOKEN:-}" ]; then
    echo "HCLOUD_TOKEN is not set — run this from inside infra/ so direnv loads it." >&2
    exit 2
fi

fetch() { curl -sS -H "Authorization: Bearer ${HCLOUD_TOKEN}" "${API}/$1"; }

mode=advertised
if [ "${1:-}" = "--probe" ]; then
    mode=probe
    shift
fi
export TARGET="${1:-}"

if [ "$mode" = "probe" ]; then
    python3 - <<'PYTHON'
"""Settle it by ordering. See the header: the advertisement has been wrong in both directions."""
import json
import os
import subprocess
import sys
import time

API = "https://api.hetzner.cloud/v1"
TOKEN = os.environ["HCLOUD_TOKEN"]
target = os.environ.get("TARGET", "")


def parse(spec):
    out = []
    for entry in spec.split(";"):
        if not entry:
            continue
        name, _, rest = entry.partition("=")
        location, _, types = rest.partition(":")
        out.append((name, location, types.split(",")))
    return out


required = parse(os.environ["ENVIRONMENTS"])
watched = {(name, loc): types for name, loc, types in parse(os.environ["WATCH"])}

if target:
    known = ", ".join(sorted(name for name, _, _ in required))
    required = [e for e in required if e[0] == target]
    if not required:
        print("unknown environment '%s' — expected one of: %s" % (target, known), file=sys.stderr)
        sys.exit(2)


def api(method, path, body=None):
    cmd = ["curl", "-sS", "-X", method, "-H", "Authorization: Bearer " + TOKEN, API + path,
           "-w", "\n%{http_code}"]
    if body is not None:
        cmd += ["-H", "Content-Type: application/json", "-d", json.dumps(body)]
    out = subprocess.run(cmd, capture_output=True, text=True).stdout
    payload, _, code = out.rpartition("\n")
    try:
        return int(code), (json.loads(payload) if payload.strip() else {})
    except ValueError:
        return int(code), {"raw": payload[:400]}


def delete(name):
    """Delete the probe and prove it is gone. A leaked probe is a server nobody knows they own."""
    _, found = api("GET", "/servers?name=" + name)
    for server in found.get("servers", []):
        api("DELETE", "/servers/%s" % server["id"])
    for _ in range(12):
        _, found = api("GET", "/servers?name=" + name)
        if not found.get("servers"):
            return True
        time.sleep(3)
    return False


def probe(server_type, location):
    """Order one bare server. Returns (orderable, detail), and deletes anything it created."""
    name = "capacity-probe-%s-%s" % (server_type, location)
    _, existing = api("GET", "/servers?name=" + name)
    if existing.get("servers"):
        return None, "a server named %s already exists — not touching it" % name
    created = None
    try:
        code, body = api("POST", "/servers", {
            "name": name,
            "server_type": server_type,
            "location": location,
            # Hetzner resolves the image name against the type's architecture, so one name serves
            # both. No IPv4: it is billed separately and nothing is going to connect to this.
            "image": "debian-12",
            "start_after_create": False,
            "public_net": {"enable_ipv4": False, "enable_ipv6": True},
            "labels": {"purpose": "capacity-probe"},
        })
        if code in (200, 201):
            created = body["server"]["id"]
            return True, "ordered, then deleted"
        error = body.get("error", {})
        return False, "%s (%s)" % (error.get("message") or json.dumps(body)[:90], error.get("code"))
    finally:
        if created and not delete(name):
            print("   !! %s STILL EXISTS — delete it by hand" % name, file=sys.stderr)


def report(server_type, orderable, detail, suffix=""):
    state = "ORDERABLE" if orderable else ("SKIPPED" if orderable is None else "refused")
    line = "  %-8s %-9s %s%s" % (server_type, state, suffix, detail if not orderable else "")
    print(line.rstrip())


blocked = []
for name, location, types in required:
    print("%s  (%s)" % (name, location))
    for wanted in types:
        orderable, detail = probe(wanted, location)
        report(wanted, orderable, detail)
        if orderable is False:
            blocked.append("%s/%s" % (name, wanted))
    for wanted in watched.get((name, location), []):
        orderable, detail = probe(wanted, location)
        report(wanted, orderable, detail, suffix="(watch only) ")

_, servers = api("GET", "/servers")
leaked = [s["name"] for s in servers.get("servers", []) if s["name"].startswith("capacity-probe-")]
if leaked:
    print("\nPROBES LEFT BEHIND — delete these by hand: %s" % ", ".join(leaked), file=sys.stderr)
    sys.exit(3)

if blocked:
    print("\nBlocked: %s" % ", ".join(blocked))
    sys.exit(1)

print("\nEvery required type was ordered and deleted again. That is the real answer, not an advertisement.")
PYTHON
    exit $?
fi

python3 - "$(fetch 'server_types?per_page=100')" "$(fetch datacenters)" <<'PYTHON'
import json
import os
import sys

names = {t["id"]: t["name"] for t in json.loads(sys.argv[1])["server_types"]}
datacenters = json.loads(sys.argv[2])["datacenters"]

zone = os.environ["NETWORK_ZONE"]
target = os.environ.get("TARGET", "")

# location -> the types Hetzner currently advertises as available there
available = {}
for dc in datacenters:
    if dc["location"]["network_zone"] != zone:
        continue
    available.setdefault(dc["location"]["name"], set()).update(
        names[i] for i in dc["server_types"]["available"] if i in names
    )


def parse(spec):
    out = []
    for entry in spec.split(";"):
        if not entry:
            continue
        name, _, rest = entry.partition("=")
        location, _, types = rest.partition(":")
        out.append((name, location, types.split(",")))
    return out


environments = parse(os.environ["ENVIRONMENTS"])
watched = {(name, loc): types for name, loc, types in parse(os.environ["WATCH"])}

if target == "--all":
    for location in sorted(available):
        orderable = sorted(available[location])
        print(f"{location}: {', '.join(orderable) if orderable else 'NOTHING AVAILABLE'}")
    sys.exit(0)

if target:
    known = ", ".join(sorted(e[0] for e in environments))
    environments = [e for e in environments if e[0] == target]
    if not environments:
        print(f"unknown environment '{target}' — expected one of: {known}, or --all", file=sys.stderr)
        sys.exit(2)

blocked = []
for name, location, types in environments:
    print(f"{name}  ({location})")
    for wanted in types:
        if wanted in available.get(location, set()):
            print(f"  {wanted:<8} advertised available")
            continue
        blocked.append(f"{name}/{wanted}")
        # Where else it could go matters: moving an environment is two variables, plus destroying
        # any Primary IPs already created, which are location-bound.
        elsewhere = sorted(loc for loc, types_ in available.items() if wanted in types_)
        if elsewhere:
            print(f"  {wanted:<8} NOT in {location} — advertised in: {', '.join(elsewhere)}")
        else:
            print(f"  {wanted:<8} UNAVAILABLE anywhere in {zone}")
    for wanted in watched.get((name, location), []):
        where = sorted(loc for loc, types_ in available.items() if wanted in types_)
        state = f"advertised in: {', '.join(where)}" if where else f"not advertised in {zone}"
        print(f"  {wanted:<8} (watch only) {state}")

print("\nThat is the advertisement, which has been wrong in both directions — see the header. Use")
print("--probe to settle it by ordering.")

if blocked:
    print(f"\nBlocked: {', '.join(blocked)}")
    print("History and trend (community-run, polls every minute):")
    print("  https://radar.iodev.org/cloud-status?arch=arm")
    sys.exit(1)
PYTHON
