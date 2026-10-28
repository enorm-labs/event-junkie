#!/usr/bin/env bash
#
# Can each environment's hardware actually be bought right now?
#
#   cd infra && ./check-capacity.sh              # every environment, from the advertisement
#   cd infra && ./check-capacity.sh staging      # one environment
#   cd infra && ./check-capacity.sh --all        # inventory: everything advertised in eu-central
#   cd infra && ./check-capacity.sh --probe      # ORDERS one bare server per type and deletes it
#   cd infra && ./check-capacity.sh --probe production
#   cd infra && ./check-capacity.sh cx33@nbg1,cx23@nbg1           # the advertisement for any type: free
#   cd infra && ./check-capacity.sh --probe cx33@nbg1,cx23@nbg1   # any type, before an environment uses it
#
# Waiting for a type costs nothing on the advertisement, and an order only once it says yes:
#
#   until ./check-capacity.sh cx33@nbg1,cx23@nbg1 && ./check-capacity.sh --probe cx33@nbg1,cx23@nbg1; do sleep 900; done
#
# The default mode reports what Hetzner advertises, `server_types[].locations[].available` since
# `/v1/datacenters` answered 410 (#4159). That is not what an order will do; the two disagreed three times out of four, in both directions. `--probe` answers the
# question: a real order per type (no IPs, no network, `start_after_create: false`), deleted on
# success and the deletion verified. A refusal costs nothing and returns in ~0.1 s; a success is
# billed by the hour and lives for seconds.
#
# A green probe means orderable at that instant only: ORDERABLE for cx33 and cx23 in fsn1 was
# followed thirty minutes later by `error during placement (resource_unavailable)` on the cx33,
# after the cx23 was created. Probe immediately before the apply, and re-probe after a failure:
#
#   until ./check-capacity.sh --probe production; do sleep 1800; done && say "production can go ARM"
#
# `resource_unavailable` means sold here and out of stock, so it can come back. `unsupported
# location` has not been seen to resolve, and it is the code ARM is refused with across eu-central.
# Server Radar keeps the trend, community-run: <https://radar.iodev.org/cloud-status?arch=arm>.

set -euo pipefail

readonly API=https://api.hetzner.cloud/v1

# What each environment REQUIRES, as `<name>=<location>:<type>[,<type>…]`; these decide the exit
# code. KEEP IN STEP WITH infra/environments/*/main.tf, which is the truth and this a copy; when
# they drift this answers a question nobody is asking and says nothing about it (#460).
export ENVIRONMENTS="staging=nbg1:cx33;production=nbg1:cpx32,cpx22"

# Types worth being told about but nothing depends on. Reported, never counted: a watch entry
# that turns the script red makes the `until` loop above permanently false, as the old `cx43`
# entry did. staging: cx43 (16 GB, €19.03) is supported in nbg1 and out of stock there, orderable
# in fsn1 where the location-bound Primary IPs and volume make it useless (#460). production: the
# cx pair it ran on until cx could not be placed (#4128), and the ARM pair, if it became buyable and still cheaper.
export WATCH="staging=nbg1:cx43;production=nbg1:cx33,cx23,cax21,cax11"

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

# `<type>@<location>[,…]` probes exactly those, so a type no environment uses yet can be settled.
if "@" in target:
    by_location = {}
    for item in target.split(","):
        wanted, _, location = item.partition("@")
        if not wanted or not location:
            print("expected <type>@<location>, got '%s'" % item, file=sys.stderr)
            sys.exit(2)
        by_location.setdefault(location, []).append(wanted)
    required = [("requested", location, types) for location, types in by_location.items()]
    watched = {}
    target = ""

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
            # Hetzner resolves the image name against the type's architecture. No IPv4: billed separately,
            # and nothing connects to this.
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

# 50 is the API's page cap; the catalogue has 26 types. More than one page fails loudly below.
python3 - "$(fetch 'server_types?per_page=50')" "$(fetch locations)" <<'PYTHON'
import json
import os
import sys

types_page = json.loads(sys.argv[1])
locations_page = json.loads(sys.argv[2])
for page in (types_page, locations_page):
    if "error" in page:
        print("Hetzner API error: %s" % json.dumps(page["error"])[:200], file=sys.stderr)
        sys.exit(2)
if types_page["meta"]["pagination"]["next_page"]:
    print("server_types has a second page now; read it before trusting this", file=sys.stderr)
    sys.exit(2)

zone = os.environ["NETWORK_ZONE"]
target = os.environ.get("TARGET", "")
in_zone = {loc["name"] for loc in locations_page["locations"] if loc["network_zone"] == zone}

# location -> the types Hetzner currently advertises as available there
available = {loc: set() for loc in in_zone}
for server_type in types_page["server_types"]:
    for loc in server_type.get("locations", []):
        if loc["name"] in in_zone and loc.get("available"):
            available[loc["name"]].add(server_type["name"])


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

# `<type>@<location>[,…]`: exit 0 only when Hetzner advertises every one of them, a free check
# to wait on before paying for a --probe.
if "@" in target:
    missing = []
    for item in target.split(","):
        wanted, _, location = item.partition("@")
        state = "advertised available" if wanted in available.get(location, set()) else "not advertised"
        print(f"  {wanted:<8} {location:<5} {state}")
        if state != "advertised available":
            missing.append(item)
    sys.exit(1 if missing else 0)

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
        # Moving an environment is two variables plus destroying any location-bound Primary IPs.
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
