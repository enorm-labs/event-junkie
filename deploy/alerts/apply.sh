#!/usr/bin/env bash
#
# Push the #271 alert rules into OpenObserve, or check that they can fire at all.
#
# Alerts are API objects, not Kubernetes ones, so Flux cannot reconcile them —
# the same seam `../dashboards/apply.sh` sits on, with the same consequence:
# nothing in the cluster notices if somebody edits a rule in the UI, and nothing
# restores these after a node rebuild unless this is run. CLUSTER_BOOTSTRAP.md is
# what has to remember.
#
# It reaches the cluster over the WireGuard tunnel via the node, like the rest of
# docs/ops/CLUSTER_ACCESS.md. There is no ingress route to OpenObserve.
#
#   ./apply.sh                # create or update every rule in alerts.json
#   ./apply.sh --check        # evaluate each rule's query, change nothing
#   ./apply.sh --diff         # compare the cluster's rules to alerts.json, change nothing
#
# `--check` is the one to run after editing `gen_alerts.py`, and it answers a
# question the UI does not: whether the query returns anything at all. A rule
# whose PromQL matches no series never fires and looks exactly like health.
#
# `--diff` answers a DIFFERENT question, and the two are easy to confuse: whether
# what is running is what this repository says. `--check` evaluates the generated
# file's queries, so it stays green while the cluster runs something else entirely
# — which is precisely what happened for 26 hours after `ej-site-down` was fixed
# (#702). Run it after a deploy, and whenever an alert's behaviour surprises you.
set -euo pipefail

readonly NODE="${EJ_NODE:-ops@10.10.1.1}"
readonly SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
readonly ORG="${EJ_O2_ORG:-default}"

cd "$(dirname "$0")"

check_only=false
diff_only=false
for arg in "$@"; do
    case "$arg" in
        --check) check_only=true ;;
        --diff) diff_only=true ;;
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

# Generated, never hand-edited — the same rule the dashboards keep. Regenerating
# here means `--check` can never validate a file that differs from the generator.
python3 gen_alerts.py > alerts.json
echo "rules: $(python3 -c 'import json;print(len(json.load(open("alerts.json"))))') (from gen_alerts.py)"

ssh_node() { ssh -o ConnectTimeout=10 -o BatchMode=yes -i "$SSH_KEY" "$NODE" "$@"; }

ssh_node 'cat > /tmp/ej-alerts.json' < alerts.json

# The credentials live in the flux-system copy of the Secret, NOT the observability
# one — the observability copy holds only the four keys the chart itself reads.
# Reaching for the wrong one produces a flat 401 that reads like a wrong password.
if $diff_only; then
    ssh_node 'cat > /tmp/ej-diff-alerts.py' < diff_alerts.py
    ssh_node "
        AUTH=\$(sudo k3s kubectl -n flux-system get secret openobserve-credentials -o jsonpath='{.data.O2_BASIC_AUTH_HEADER}' | base64 -d)
        SVC=\$(sudo k3s kubectl -n observability get svc openobserve-openobserve-standalone -o jsonpath='{.spec.clusterIP}')
        python3 /tmp/ej-diff-alerts.py \"\$AUTH\" \"\$SVC\" '$ORG' /tmp/ej-alerts.json
    "
    exit $?
fi

if $check_only; then
    ssh_node 'cat > /tmp/ej-check-alerts.py' < check_alerts.py
    ssh_node "
        AUTH=\$(sudo k3s kubectl -n flux-system get secret openobserve-credentials -o jsonpath='{.data.O2_BASIC_AUTH_HEADER}' | base64 -d)
        SVC=\$(sudo k3s kubectl -n observability get svc openobserve-openobserve-standalone -o jsonpath='{.spec.clusterIP}')
        python3 /tmp/ej-check-alerts.py \"\$AUTH\" \"\$SVC\" /tmp/ej-alerts.json
    "
    exit $?
fi

ssh_node 'cat > /tmp/ej-apply-alerts.py' < apply_alerts.py
ssh_node "
    AUTH=\$(sudo k3s kubectl -n flux-system get secret openobserve-credentials -o jsonpath='{.data.O2_BASIC_AUTH_HEADER}' | base64 -d)
    SVC=\$(sudo k3s kubectl -n observability get svc openobserve-openobserve-standalone -o jsonpath='{.spec.clusterIP}')
    python3 /tmp/ej-apply-alerts.py \"\$AUTH\" \"\$SVC\" '$ORG' /tmp/ej-alerts.json
"

cat <<'EOF'

Firings land in the `alert_history` stream, because delivery waits on #271 item 4:
  kubectl --context event-junkie-staging -n observability port-forward svc/openobserve-openobserve-standalone 5080:5080
  http://localhost:5080/web/alerts        — the rules
  http://localhost:5080/web/logs          — stream `alert_history`, one row per firing
EOF
