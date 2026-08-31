#!/usr/bin/env bash
#
# Import (or re-import) an OpenObserve dashboard, and check that its panels return data.
#
# OpenObserve dashboards are API objects, not Kubernetes ones, so Flux cannot reconcile them and
# this is the seam where GitOps stops. The JSON next to this script is the source of truth; this is
# what pushes it. Re-running replaces rather than duplicates — see import_dashboard.py for why that
# is matched on title.
#
# It reaches the cluster over the WireGuard tunnel via the node, like the rest of
# docs/ops/CLUSTER_ACCESS.md. There is no ingress route to OpenObserve, deliberately.
#
#   ./apply.sh                       # import is-it-healthy.json, on staging
#   ./apply.sh --check               # validate every panel query returns data, change nothing
#   ./apply.sh --diff                # compare the cluster's copy to this file, change nothing
#   ./apply.sh other.json            # some other dashboard in this directory
#   EJ_NODE=ops@10.10.0.1 ./apply.sh # any of the above, against production
#
# `--check` and `--diff` answer different questions and neither substitutes for the other:
# `--check` asks whether the panels in THIS FILE would return data, `--diff` asks whether the
# cluster is running this file at all. The alerts directory grew the same pair for the same
# reason — see ../alerts/diff_alerts.py and #702.
#
# The real work happens in two Python files that are copied to the node and run there. That is
# deliberate: an earlier version inlined it as a remote shell script and the nested quoting was
# three levels deep and unreadable.
set -euo pipefail

readonly NODE="${EJ_NODE:-ops@10.10.1.1}"
readonly SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
readonly ORG="${EJ_O2_ORG:-default}"

# **Which cluster this writes to is `EJ_NODE` and nothing else** (#880). Both environments run an
# OpenObserve now, this dashboard is one file imported into each, and the default is staging — so a
# production run that forgets the variable succeeds against staging and says nothing. The context is
# derived here only to print an accurate hint at the end; the tunnel address is what selects.
case "$NODE" in
    *10.10.0.1*) readonly CONTEXT="event-junkie-production" ;;
    *) readonly CONTEXT="event-junkie-staging" ;;
esac

cd "$(dirname "$0")"

check_only=false
diff_only=false
file=is-it-healthy.json
for arg in "$@"; do
    case "$arg" in
        --check) check_only=true ;;
        --diff) diff_only=true ;;
        *.json) file="$arg" ;;
        *)
            echo "unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

[ -f "$file" ] || {
    echo "no such dashboard file: $file" >&2
    exit 2
}
python3 -c 'import json,sys; json.load(open(sys.argv[1]))' "$file" || {
    echo "$file is not valid JSON" >&2
    exit 2
}

ssh_node() { ssh -o ConnectTimeout=10 -o BatchMode=yes -i "$SSH_KEY" "$NODE" "$@"; }

echo "dashboard: $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["title"])' "$file")  (from $file)"
echo "cluster: $CONTEXT ($NODE)"

ssh_node 'cat > /tmp/ej-dashboard.json' < "$file"

# The credentials live in the flux-system copy of the Secret, NOT the observability one. The
# observability copy holds only the four keys the chart itself reads; O2_BASIC_AUTH_HEADER is there
# for Flux's `valuesFrom`, which resolves Secrets in the HelmRelease's own namespace. Reaching for
# the observability copy produces a flat 401 that reads like a wrong password.
if $check_only && $diff_only; then
    echo "--check and --diff answer different questions; run them one at a time" >&2
    exit 2
fi

if $diff_only; then
    ssh_node 'cat > /tmp/ej-diff-dashboard.py' < diff_dashboard.py
    ssh_node "
        AUTH=\$(sudo k3s kubectl -n flux-system get secret openobserve-credentials -o jsonpath='{.data.O2_BASIC_AUTH_HEADER}' | base64 -d)
        SVC=\$(sudo k3s kubectl -n observability get svc openobserve-openobserve-standalone -o jsonpath='{.spec.clusterIP}')
        python3 /tmp/ej-diff-dashboard.py \"\$AUTH\" \"\$SVC\" '$ORG' /tmp/ej-dashboard.json
    "
    exit $?
fi

if $check_only; then
    ssh_node 'cat > /tmp/ej-check.py' < check_panels.py
    ssh_node "
        AUTH=\$(sudo k3s kubectl -n flux-system get secret openobserve-credentials -o jsonpath='{.data.O2_BASIC_AUTH_HEADER}' | base64 -d)
        SVC=\$(sudo k3s kubectl -n observability get svc openobserve-openobserve-standalone -o jsonpath='{.spec.clusterIP}')
        python3 /tmp/ej-check.py \"\$AUTH\" \"\$SVC\" /tmp/ej-dashboard.json
    "
    exit $?
fi

ssh_node 'cat > /tmp/ej-import.py' < import_dashboard.py
ssh_node "
    AUTH=\$(sudo k3s kubectl -n flux-system get secret openobserve-credentials -o jsonpath='{.data.O2_BASIC_AUTH_HEADER}' | base64 -d)
    SVC=\$(sudo k3s kubectl -n observability get svc openobserve-openobserve-standalone -o jsonpath='{.spec.clusterIP}')
    python3 /tmp/ej-import.py \"\$AUTH\" \"\$SVC\" '$ORG' /tmp/ej-dashboard.json
"

cat <<EOF

Open it — OpenObserve is not routed through the ingress, so port-forward:
  kubectl --context $CONTEXT -n observability port-forward svc/openobserve-openobserve-standalone 5080:5080
  http://localhost:5080/web/dashboards
EOF
