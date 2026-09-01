#!/usr/bin/env bash
#
# Import (or re-import) this directory's OpenObserve dashboards, and check their panels return data.
#
# OpenObserve dashboards are API objects, not Kubernetes ones, so Flux cannot reconcile them and
# this is the seam where GitOps stops. The JSON next to this script is the source of truth; this is
# what pushes it. Re-running replaces rather than duplicates — see import_dashboard.py for why that
# is matched on title.
#
# It reaches the cluster over the WireGuard tunnel via the node, like the rest of
# docs/ops/CLUSTER_ACCESS.md. There is no ingress route to OpenObserve, deliberately.
#
#   ./apply.sh                       # import every *.json here, on staging
#   ./apply.sh --check               # validate every panel query returns data, change nothing
#   ./apply.sh --diff                # compare the cluster's copies to these files, change nothing
#   ./apply.sh other.json            # just one of them, for iterating
#   EJ_NODE=ops@10.10.0.1 ./apply.sh # any of the above, against production
#
# Three checks, three questions, and no two of them substitute for each other. `--check` asks
# whether the panels in THIS FILE would return data, `--diff` asks whether the cluster is running
# this file at all, and `lint_dashboard.py` — which runs unconditionally, below — asks whether
# OpenObserve can DRAW what the other two are validating. The alerts directory grew the first pair
# for the same reason; see ../alerts/diff_alerts.py and #702. The third is #969, where every query
# returned data and five panels still rendered nothing.
#
# The real work happens in Python files that are copied to the node and run there. That is
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
named=""
for arg in "$@"; do
    case "$arg" in
        --check) check_only=true ;;
        --diff) diff_only=true ;;
        *.json) named="$arg" ;;
        *)
            echo "unknown argument: $arg" >&2
            exit 2
            ;;
    esac
done

if $check_only && $diff_only; then
    echo "--check and --diff answer different questions; run them one at a time" >&2
    exit 2
fi

# **Every dashboard in this directory, unless one is named.** With a second dashboard the file that
# is not named is the one that drifts, which is the failure `--diff` exists to catch reintroduced by
# having somewhere for it to hide. Naming one is still supported, for iterating on it.
files=()
if [ -n "$named" ]; then
    files=("$named")
else
    for f in *.json; do files+=("$f"); done
fi

for file in "${files[@]}"; do
    [ -f "$file" ] || {
        echo "no such dashboard file: $file" >&2
        exit 2
    }
done

# Static, offline, and BEFORE the network — it runs for `--check` and `--diff` too, so there is no
# invocation that reaches a cluster without it, and every file is linted before any of them is
# pushed. This is the check `--check` is not: it validates what OpenObserve will DRAW, where
# `--check` validates what the queries RETURN. #969 was five panels typed `stat` whose queries all
# returned data, on a grid a quarter of the right width; `--check` was green for both. It also reads
# the JSON, so a parse error surfaces here.
for file in "${files[@]}"; do
    python3 lint_dashboard.py "$file" || exit $?
done

ssh_node() { ssh -o ConnectTimeout=10 -o BatchMode=yes -i "$SSH_KEY" "$NODE" "$@"; }

# The credentials live in the flux-system copy of the Secret, NOT the observability one. The
# observability copy holds only the four keys the chart itself reads; O2_BASIC_AUTH_HEADER is there
# for Flux's `valuesFrom`, which resolves Secrets in the HelmRelease's own namespace. Reaching for
# the observability copy produces a flat 401 that reads like a wrong password.
run_on_node() {
    ssh_node "
        AUTH=\$(sudo k3s kubectl -n flux-system get secret openobserve-credentials -o jsonpath='{.data.O2_BASIC_AUTH_HEADER}' | base64 -d)
        SVC=\$(sudo k3s kubectl -n observability get svc openobserve-openobserve-standalone -o jsonpath='{.spec.clusterIP}')
        python3 $1
    "
}

echo "cluster: $CONTEXT ($NODE)"

if $diff_only; then
    helper=diff_dashboard.py
elif $check_only; then
    helper=check_panels.py
else
    helper=import_dashboard.py
fi
ssh_node 'cat > /tmp/ej-helper.py' < "$helper"

status=0
for file in "${files[@]}"; do
    echo
    echo "dashboard: $(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["title"])' "$file")  (from $file)"
    ssh_node 'cat > /tmp/ej-dashboard.json' < "$file"
    if $check_only; then
        # check_panels.py takes no org: it queries Prometheus endpoints, which are org-scoped in the
        # URL the script builds for itself.
        run_on_node "/tmp/ej-helper.py \"\$AUTH\" \"\$SVC\" /tmp/ej-dashboard.json" || status=1
    else
        run_on_node "/tmp/ej-helper.py \"\$AUTH\" \"\$SVC\" '$ORG' /tmp/ej-dashboard.json" || status=1
    fi
done

if ! $check_only && ! $diff_only; then
    cat <<EOF

Open them — OpenObserve is not routed through the ingress, so port-forward:
  kubectl --context $CONTEXT -n observability port-forward svc/openobserve-openobserve-standalone 5080:5080
  http://localhost:5080/web/dashboards
EOF
fi

exit $status
