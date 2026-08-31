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
#   ./apply.sh                # create or update every rule in alerts.json, on staging
#   ./apply.sh --check        # evaluate each rule's query, change nothing
#   ./apply.sh --diff         # compare the cluster's rules to alerts.json, change nothing
#   EJ_NODE=ops@10.10.0.1 ./apply.sh   # any of the three, against production
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

# **Which cluster this writes to is `EJ_NODE` and nothing else** (#880). Both environments run an
# OpenObserve now, the rules are one file applied to each, and the default is staging — so a
# production run that forgets the variable succeeds against staging and says nothing. The context is
# derived here only to print an accurate hint at the end; the tunnel address is what selects.
case "$NODE" in
    *10.10.0.1*) readonly CONTEXT="event-junkie-production" ;;
    *) readonly CONTEXT="event-junkie-staging" ;;
esac

# The same answer without the `event-junkie-` prefix, for the template's `environment`
# field (#928). OpenObserve has no substitution for it, so it is baked into the body at
# apply time — and it is the only field in a firing that says which cluster is broken.
readonly ENVIRONMENT="${CONTEXT#event-junkie-}"

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
echo "cluster: $CONTEXT ($NODE)"

ssh_node() { ssh -o ConnectTimeout=10 -o BatchMode=yes -i "$SSH_KEY" "$NODE" "$@"; }

ssh_node 'cat > /tmp/ej-alerts.json' < alerts.json

# The credentials live in the flux-system copy of the Secret, NOT the observability
# one — the observability copy holds only the four keys the chart itself reads.
# Reaching for the wrong one produces a flat 401 that reads like a wrong password.
# `alert_objects.py` goes with it: both scripts import the template and destination
# from there, because two copies of the same expected object is the bug this whole
# check exists to catch. Plain name, not the `ej-` prefix the other files use — it
# has to be importable, and a module name cannot carry a dash.
if $diff_only; then
    ssh_node 'cat > /tmp/alert_objects.py' < alert_objects.py
    ssh_node 'cat > /tmp/ej-diff-alerts.py' < diff_alerts.py
    ssh_node "
        AUTH=\$(sudo k3s kubectl -n flux-system get secret openobserve-credentials -o jsonpath='{.data.O2_BASIC_AUTH_HEADER}' | base64 -d)
        SVC=\$(sudo k3s kubectl -n observability get svc openobserve-openobserve-standalone -o jsonpath='{.spec.clusterIP}')
        python3 /tmp/ej-diff-alerts.py \"\$AUTH\" \"\$SVC\" '$ORG' /tmp/ej-alerts.json '$ENVIRONMENT'
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

ssh_node 'cat > /tmp/alert_objects.py' < alert_objects.py
ssh_node 'cat > /tmp/ej-apply-alerts.py' < apply_alerts.py
ssh_node "
    AUTH=\$(sudo k3s kubectl -n flux-system get secret openobserve-credentials -o jsonpath='{.data.O2_BASIC_AUTH_HEADER}' | base64 -d)
    SVC=\$(sudo k3s kubectl -n observability get svc openobserve-openobserve-standalone -o jsonpath='{.spec.clusterIP}')
    python3 /tmp/ej-apply-alerts.py \"\$AUTH\" \"\$SVC\" '$ORG' /tmp/ej-alerts.json '$ENVIRONMENT'
"

cat <<EOF

Firings land in the \`alert_history\` stream, because delivery waits on #271 item 4:
  kubectl --context $CONTEXT -n observability port-forward svc/openobserve-openobserve-standalone 5080:5080
  http://localhost:5080/web/alerts        — the rules
  http://localhost:5080/web/logs          — stream \`alert_history\`, one row per firing
EOF
