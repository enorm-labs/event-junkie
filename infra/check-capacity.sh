#!/usr/bin/env bash
#
# Is the hardware this configuration asks for actually orderable right now?
#
# Hetzner sells out of server types, and when it does `tofu apply` fails at the very last step
# with `error during placement (resource_unavailable)` — after the network, firewall and Primary
# IPs have already been created. This asks the question first, in about a second.
#
# On 2026-08-11 the entire CAX (ARM) line was unavailable across every eu-central location, and
# fsn1 had nothing at all. Nothing was wrong with the configuration; there were simply no machines.
#
#   cd infra && ./check-capacity.sh          # what this repo needs
#   cd infra && ./check-capacity.sh --all    # everything orderable in eu-central
#
# Exits 0 only when every required type is available somewhere, so it also works as a waiter:
#
#   until ./check-capacity.sh; do sleep 1800; done && say "capacity is back"
#
# This is a snapshot of *now*, taken from Hetzner's own API, which is what an apply will actually
# hit. For the shape of the problem over time — how long a type has been out, whether it flickers
# back hourly or has been gone for a week — Server Radar polls every minute and keeps the history:
#
#   https://radar.iodev.org/cloud-status?arch=arm
#
# Community-run (github.com/elsbrock/hetzner-radar), not Hetzner. Treat it as the trend and this
# script as the fact.

set -euo pipefail

readonly API=https://api.hetzner.cloud/v1

# Keep in step with the `*_server_type` values in infra/environments/*.
export REQUIRED_TYPES=cax11,cax21
export NETWORK_ZONE=eu-central
export PREFERRED_LOCATION=fsn1
export SHOW_ALL=${1:-}

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

required = os.environ["REQUIRED_TYPES"].split(",")
zone = os.environ["NETWORK_ZONE"]
preferred = os.environ["PREFERRED_LOCATION"]

available = {}
for dc in datacenters:
    if dc["location"]["network_zone"] != zone:
        continue
    location = dc["location"]["name"]
    available.setdefault(location, set()).update(
        names[i] for i in dc["server_types"]["available"] if i in names
    )

if os.environ.get("SHOW_ALL") == "--all":
    for location in sorted(available):
        orderable = sorted(available[location])
        print(f"{location}: {', '.join(orderable) if orderable else 'NOTHING AVAILABLE'}")
    print()

missing = []
for wanted in required:
    where = sorted(loc for loc, orderable in available.items() if wanted in orderable)
    if not where:
        missing.append(wanted)
        print(f"  {wanted:<8} UNAVAILABLE everywhere in {zone}")
        continue
    note = "  <- preferred" if preferred in where else "  (NOT in the preferred location)"
    print(f"  {wanted:<8} available in: {', '.join(where)}{note}")

if missing:
    print(f"\nStill waiting on: {', '.join(missing)}")
    print("History and trend (community-run, polls every minute):")
    print("  https://radar.iodev.org/cloud-status?arch=arm")
    sys.exit(1)

print("\nAll required types are orderable.")
PYTHON
