#!/usr/bin/env bash
#
# Does Hetzner currently advertise the hardware each environment asks for?
#
# Hetzner sells out of server types, and when it does `tofu apply` fails at the very last step —
# after the network, firewall and Primary IPs have already been created. This asks first, in about
# a second.
#
#   cd infra && ./check-capacity.sh              # every environment
#   cd infra && ./check-capacity.sh staging      # one environment — exits 0 when it can be applied
#   cd infra && ./check-capacity.sh --all        # inventory: everything available in eu-central
#
# The single-environment form is the one to wait on, because the environments no longer want the
# same location or the same hardware:
#
#   until ./check-capacity.sh staging; do sleep 1800; done && say "staging can be applied"
#
# READ THIS BEFORE TRUSTING A GREEN RESULT.
#
# **"Available" here is what Hetzner advertises, not a promise that an order will succeed**, and the
# two have been observed to disagree. This script used to claim it reported "what an apply will
# actually hit". It does not, and cannot: there is no dry-run for a server order.
#
#   2026-08-11  The entire CAX (ARM) line was unavailable across every eu-central location and fsn1
#               had nothing at all. The apply failed with `error during placement
#               (resource_unavailable)`. Here the script and the order path agreed.
#
#   2026-08-13  This script reported cax11 available in nbg1. The `datacenters` endpoint listed it
#               under `available`, and cax11's own pricing lists nbg1 as a location it is sold in.
#               The order was refused anyway:
#
#                 Error: unsupported location for server type (invalid_input)
#
#               Note that is a *different* error from a sold-out one, which is why it reads like a
#               configuration fault rather than a capacity one. It is not: nothing about nbg1 or
#               cax11 is invalid. Treat it as capacity wearing the wrong error code.
#
#               Settled by ordering a bare cax11 in nbg1 through the API — no Primary IPs, no
#               network, no firewall, `start_after_create: false`. Same refusal. So it is Hetzner's
#               side and not our configuration, three refusals deep, with the type still advertised
#               as available at the moment of each one.
#
#               Staging moved to cpx22 (x86) as a result. It is the cheapest thing in eu-central
#               that can actually be bought with 2 vCPU and 4 GB — €23.19 against cax11's €7.13 —
#               and the shortage is not an ARM shortage: the whole cx line is gone too, including
#               cx23 at €6.53, which is cheaper than the ARM plan ever was. Both are worth watching
#               with `--all`; whichever returns first is worth moving back to.
#
# So: a green result means "worth trying", not "will work". A red result is reliable — if Hetzner
# does not advertise it, you certainly cannot order it.
#
# For the shape of the problem over time — how long a type has been out, whether it flickers back
# hourly or has been gone for a week — Server Radar polls every minute and keeps the history:
#
#   https://radar.iodev.org/cloud-status?arch=arm
#
# Community-run (github.com/elsbrock/hetzner-radar), not Hetzner. It is the trend; this is the
# current advertisement; neither is the order path.

set -euo pipefail

readonly API=https://api.hetzner.cloud/v1

# What each environment asks for, as `<name>=<location>:<type>[,<type>…]`.
#
# KEEP IN STEP WITH infra/environments/*/main.tf — the `location` and `*_server_type` values there
# are the truth and this is a copy. They diverged once already: staging moved to nbg1 on 2026-08-13
# while this script still called fsn1 "the preferred location" for everything, so its output
# answered a question nobody was asking.
#
# `cx43` is listed for staging alongside the type actually in use. It is not a candidate to move to
# today — it was refused with `resource_unavailable` on 2026-08-20, which unlike `unsupported
# location` means supported-here-but-out-of-stock and can therefore come back. 16 GB for €19.03 is
# worth being told about if it does; watching it costs one entry.
export ENVIRONMENTS="staging=nbg1:cx33,cx43;production=fsn1:cax21,cax11"
export NETWORK_ZONE=eu-central
export TARGET="${1:-}"

if [ -z "${HCLOUD_TOKEN:-}" ]; then
    echo "HCLOUD_TOKEN is not set — run this from inside infra/ so direnv loads it." >&2
    exit 2
fi

fetch() { curl -sS -H "Authorization: Bearer ${HCLOUD_TOKEN}" "${API}/$1"; }

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

environments = []
for spec in os.environ["ENVIRONMENTS"].split(";"):
    name, _, rest = spec.partition("=")
    location, _, types = rest.partition(":")
    environments.append((name, location, types.split(",")))

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

if blocked:
    print(f"\nBlocked: {', '.join(blocked)}")
    print("History and trend (community-run, polls every minute):")
    print("  https://radar.iodev.org/cloud-status?arch=arm")
    sys.exit(1)

# Deliberately not "you can apply this" — see the header. Hetzner has refused an order for hardware
# it was advertising at that moment, and the error did not look like a capacity error.
print("\nAdvertised as available. That makes an apply worth trying, not certain to succeed.")
PYTHON
