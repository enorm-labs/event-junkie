#!/usr/bin/env bash
#
# cluster-state.sh — one read-only picture of an environment: is it whole, and what is in it.
#
# Usage:
#   scripts/cluster-state.sh                    # staging
#   scripts/cluster-state.sh production
#   scripts/cluster-state.sh staging --out DIR  # also write the raw answers, for a later diff
#
# Needs the WireGuard tunnel up (scripts/ej.sh up <environment>) and a kubeconfig context named
# event-junkie-<environment>. Reads only: no apply, no restart, no write to any database. It prints
# secret names and never a secret value.
#
# Two uses, and the second is why it takes --out. It answers "is this environment healthy right now"
# on any day. Before a rebuild it also captures what the environment held, so "it came back" is a
# comparison against a file rather than an impression (#560).
#
# What it deliberately does not cover: the OpenObserve dashboards and alert rules. They are API
# objects on the node's own disk, not Kubernetes objects, so a healthy cluster says nothing about
# them. `deploy/dashboards/apply.sh --diff` and `deploy/alerts/apply.sh --diff` are the check, and
# they need the credential this script has no reason to hold.
#
# Exit code is the verdict: 0 when every assertion held, 1 when any did not.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

ENVIRONMENT=staging
OUT_DIR=

while [ $# -gt 0 ]; do
    case "$1" in
        staging | production) ENVIRONMENT="$1" ;;
        --out)
            shift
            OUT_DIR="${1:-}"
            [ -n "$OUT_DIR" ] || { echo "cluster-state: --out needs a directory" >&2; exit 2; }
            ;;
        *)
            echo "cluster-state: unknown argument '$1'" >&2
            exit 2
            ;;
    esac
    shift
done

CONTEXT="event-junkie-${ENVIRONMENT}"
NAMESPACE=event-junkie
SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"

# The k3s node's tunnel address, and the host PostgreSQL actually runs on. In staging they are the
# same machine; production keeps the database on its own node, reachable only by jump through k3s.
if [ "$ENVIRONMENT" = production ]; then
    NODE=ops@10.10.0.1
    DB_SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=10 -J "$NODE" ops@10.0.1.20)
    # cert-manager/hetzner is staging's alone: production solves HTTP-01 and holds no Hetzner token.
    EXPECTED_SECRETS="flux-system/github-dispatch flux-system/sops-age flux-system/openobserve-credentials observability/openobserve-smtp event-junkie/events-db event-junkie/event-junkie-images event-junkie/event-junkie-imgproxy event-junkie/event-junkie-translation"
else
    NODE=ops@10.10.1.1
    DB_SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=10 "$NODE")
    EXPECTED_SECRETS="flux-system/github-dispatch flux-system/sops-age flux-system/openobserve-credentials observability/openobserve-smtp event-junkie/events-db event-junkie/event-junkie-images event-junkie/event-junkie-imgproxy event-junkie/event-junkie-translation cert-manager/hetzner"
fi

NODE_SSH=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=10 "$NODE")
KUBECTL=(kubectl --context "$CONTEXT" --request-timeout=20s)

failures=0

pass() { printf '  ok    %s\n' "$1"; }
fail() {
    printf '  FAIL  %s\n' "$1" >&2
    failures=$((failures + 1))
}
section() { printf '\n== %s\n' "$1"; }
# A function rather than an inline `sed`, so the caller pipes into it and ShellCheck is not asked to
# rewrite a variable substitution that would have to handle every line of a multi-line answer.
indent() { sed 's/^/  /'; }

# Writes one answer to the --out directory, so a later run can be diffed against this one. A no-op
# without --out, which keeps every call site free of the conditional.
record() {
    # Without --out this still has to read its input. Returning early closes the pipe under the
    # writer, and a `sort` that dies of SIGPIPE takes the run with it under `set -o pipefail`.
    if [ -z "$OUT_DIR" ]; then
        cat > /dev/null
        return 0
    fi
    mkdir -p "$OUT_DIR"
    cat > "${OUT_DIR}/$1"
}

printf '%s, %s\n' "$ENVIRONMENT" "$(date -u +%Y-%m-%dT%H:%M:%SZ)"

section 'Reachability'
if "${NODE_SSH[@]}" true 2>/dev/null; then
    pass "ssh $NODE"
else
    fail "ssh $NODE - is the tunnel up? scripts/ej.sh up $ENVIRONMENT"
    echo >&2
    echo 'Nothing below can be checked without the node. Stopping.' >&2
    exit 1
fi

section 'Nodes'
nodes="$("${KUBECTL[@]}" get nodes -o wide --no-headers 2>&1 || true)"
printf '%s\n' "$nodes" | indent
record nodes.txt <<< "$nodes"
if echo "$nodes" | grep -qw Ready; then pass 'the cluster answers and its node is Ready'; else fail 'no Ready node'; fi

section 'Workloads'
bad="$("${KUBECTL[@]}" get pods -A --no-headers 2>/dev/null | awk '$4 != "Running" && $4 != "Completed"' || true)"
if [ -z "$bad" ]; then
    pass 'every pod is Running or Completed'
else
    printf '%s\n' "$bad" | indent >&2
    fail 'pods are not Running'
fi
"${KUBECTL[@]}" get pods -A --no-headers 2>/dev/null | awk '{print $1, $2, $4}' | sort | record pods.txt

section 'Flux'
for kind in gitrepositories ocirepositories helmrepositories kustomizations helmreleases; do
    while read -r line; do
        [ -n "$line" ] || continue
        name="${line%% *}"
        state="${line##* }"
        if [ "$state" = True ]; then pass "$kind $name"; else fail "$kind $name is Ready=$state"; fi
    done <<< "$("${KUBECTL[@]}" get "$kind" -A \
        -o jsonpath='{range .items[*]}{.metadata.namespace}/{.metadata.name} {.status.conditions[?(@.type=="Ready")].status}{"\n"}{end}' 2>/dev/null || true)"
done
# The HelmRelease lives in flux-system, not in the namespace it deploys into, and `spec.chart` names
# a range rather than a version. What is actually running is the revision of the last release.
chart="$("${KUBECTL[@]}" -n flux-system get helmrelease event-junkie -o jsonpath='{.status.history[0].chartVersion}' 2>/dev/null || true)"
echo "  chart: ${chart:-unknown}"
record chart.txt <<< "$chart"

section 'Secrets that nothing in the repository recreates'
present="$("${KUBECTL[@]}" get secrets -A --no-headers 2>/dev/null | awk '{print $1"/"$2}' | sort)"
record secrets.txt <<< "$present"
for want in $EXPECTED_SECRETS; do
    if echo "$present" | grep -qxF "$want"; then pass "$want"; else fail "$want is missing"; fi
done

section 'Certificates'
while read -r ns name; do
    [ -n "$name" ] || continue
    end="$("${KUBECTL[@]}" -n "$ns" get secret "$name" -o jsonpath='{.data.tls\.crt}' 2>/dev/null |
        base64 -d | openssl x509 -noout -enddate 2>/dev/null | cut -d= -f2)"
    if [ -n "$end" ]; then pass "$ns/$name expires $end"; else fail "$ns/$name holds no readable certificate"; fi
done <<< "$("${KUBECTL[@]}" get secrets -n "$NAMESPACE" --field-selector type=kubernetes.io/tls \
    -o jsonpath='{range .items[*]}{.metadata.namespace} {.metadata.name}{"\n"}{end}' 2>/dev/null || true)"

section 'Database'
counts="$("${DB_SSH[@]}" "sudo -u postgres psql -tAX -d events -c \"select (select count(*) from events.event) || ' events, ' || (select count(*) from events.artist) || ' artists, ' || (select count(*) from events.venue) || ' venues, ' || (select count(*) from events.event_source) || ' sources, ' || pg_size_pretty(pg_database_size('events'))\"" 2>/dev/null || true)"
if [ -n "$counts" ]; then pass "$counts"; else fail 'the database did not answer'; fi
record database.txt <<< "$counts"

section 'Backups'
walg="$("${DB_SSH[@]}" 'sudo -u postgres /usr/local/bin/walg check' 2>&1 || true)"
printf '%s\n' "$walg" | indent
record backups.txt <<< "$walg"
# wal-g writes an INFO line of its own before the verdict, so the check is a line that starts `ok:`
# rather than the first character of the output.
if printf '%s\n' "$walg" | grep -q '^ok:'; then
    pass 'walg check'
else
    fail 'walg check did not report ok'
fi
# `failed_count` is cumulative since `stats_reset`, so a non-zero one is not a fault: production
# carries 39 from the hour before its wal-g credential was written by hand. What makes a failure
# current is it being more recent than the last success, which is what this asks.
archiver="$("${DB_SSH[@]}" "sudo -u postgres psql -tAX -c \"select failed_count || ' ' || coalesce(last_failed_time > last_archived_time, false) || ' ' || coalesce(last_failed_time::text, 'never') from pg_stat_archiver\"" 2>/dev/null || true)"
record archiver.txt <<< "$archiver"
read -r failed stale last_failed <<< "$archiver"
if [ -z "$failed" ]; then
    fail 'pg_stat_archiver did not answer'
elif [ "$stale" = t ] || [ "$stale" = true ]; then
    fail "archiving is failing now - $failed failure(s), the last at $last_failed"
elif [ "$failed" = 0 ]; then
    pass 'no WAL segment has ever failed to archive'
else
    pass "archiving is current - $failed historical failure(s), the last at $last_failed"
fi

section 'Verdict'
if [ "$failures" -eq 0 ]; then
    echo "  $ENVIRONMENT is whole"
else
    echo "  $failures assertion(s) failed on $ENVIRONMENT" >&2
fi
[ -n "$OUT_DIR" ] && echo "  raw answers in $OUT_DIR"
echo '  not covered here: the OpenObserve dashboards and alert rules - deploy/{dashboards,alerts}/apply.sh --diff'
[ "$failures" -eq 0 ]
