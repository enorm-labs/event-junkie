#!/usr/bin/env bash
#
# Apply this directory's per-stream OpenObserve settings, or check the cluster against them (#2393).
#
# A stream's retention is an API setting, not a Kubernetes object, so Flux cannot reconcile it.
# `streams.json` is the source of truth; this pushes it, over the tunnel via the node, like
# ../dashboards/apply.sh. OpenObserve creates a stream on its first row, so a stream that does not
# exist yet is refused, not created: apply again after the collector has written to it.
#
# Usage: deploy/streams/apply.sh [--check]
#   (no argument)                    set every stream in streams.json, on staging
#   --check                          compare the cluster's settings to streams.json, change nothing
#   EJ_NODE=ops@10.10.0.1 ./apply.sh  either, against production
#
# A retention above 14 days is a privacy claim: only a stream that holds no personal data may have
# one, and docs/LEGAL.md §7.5 names each. The `o2` bucket's 90-day lifecycle rule
# (infra/bootstrap/storage.tf) is the ceiling: a longer retention loses files under the index.
set -euo pipefail
case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
        exit 0
        ;;
    "" | --check) ;;
    *)
        echo "unknown argument: $1" >&2
        exit 2
        ;;
esac
readonly NODE="${EJ_NODE:-ops@10.10.1.1}"
readonly SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
readonly ORG="${EJ_O2_ORG:-default}"
case "$NODE" in
    *10.10.0.1*) readonly CONTEXT="event-junkie-production" ;;
    *) readonly CONTEXT="event-junkie-staging" ;;
esac

cd "$(dirname "$0")"
mode=apply
[ "${1:-}" = "--check" ] && mode=check

ssh_node() { ssh -o ConnectTimeout=10 -o BatchMode=yes -i "$SSH_KEY" "$NODE" "$@"; }

echo "cluster: $CONTEXT ($NODE)"
ssh_node 'cat > /tmp/ej-streams.py' <apply_streams.py
ssh_node 'cat > /tmp/ej-streams.json' <streams.json
# The flux-system copy of the Secret holds O2_BASIC_AUTH_HEADER; ../dashboards/apply.sh says why.
ssh_node "
    AUTH=\$(sudo k3s kubectl -n flux-system get secret openobserve-credentials -o jsonpath='{.data.O2_BASIC_AUTH_HEADER}' | base64 -d)
    SVC=\$(sudo k3s kubectl -n observability get svc openobserve-openobserve-standalone -o jsonpath='{.spec.clusterIP}')
    python3 /tmp/ej-streams.py \"\$AUTH\" \"\$SVC\" '$ORG' /tmp/ej-streams.json $mode
"
