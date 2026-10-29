#!/usr/bin/env bash
#
# rebuild-node.sh — everything after the apply, from the boot check to the alerts, each step checked before the next (#4134).
#
# Usage:
#   scripts/rebuild-node.sh <staging|production> [--next] [--from <step>]
#
#   in place   boot access database secrets backups flux ready alerts verify
#   --next     boot tls access secrets flux ready verify tunnel
#   --from     start at that step; every step is safe to run again, so a rerun from the top also works
#
# In place it follows a `-replace` of the nodes. With `--next` it builds the second pair of #4128
# while the running cluster serves. The new k3s node is reached through the running one over the
# private network for the boot check and its WireGuard key, so `admin_cidrs` stays closed. Then it
# gets a tunnel, known_hosts file and kube context of its own (`cluster-access.sh --next`).
#
# `--next` leaves out what belongs to the cutover: the database roles and the wal-g credential, the
# alerts and dashboards, and the `github-dispatch` Secret, so the new cluster records no deployment.
# The app's HelmRelease is created with `suspend: true` before Flux applies the Kustomization.
# Flux does not own `suspend`, so the BFF and the importer do not run their scheduled jobs twice
# against the live database. The cutover resumes it.
#
# Only one of the two tunnels can be up, because both nodes are the same tunnel address. `tls`
# reads the certificate from the running cluster before `access` switches tunnels, so `flux` must
# run in the same invocation. `tunnel` switches back at the end.
#
# The operator runs it: it writes to a cluster, like `tofu apply`, and it never runs `tofu apply`.
# Requires: ssh, kubectl, flux, gh, jq, yq, sops, curl, security (macOS), direnv and tofu.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    echo "rebuild-node: $*" >&2
    exit 1
}

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTER_ACCESS="${EJ_CLUSTER_ACCESS:-$REPO/scripts/cluster-access.sh}"
CLUSTER_SECRETS="${EJ_CLUSTER_SECRETS:-$REPO/scripts/cluster-secrets.sh}"
ALERTS_APPLY="${EJ_ALERTS_APPLY:-$REPO/deploy/alerts/apply.sh}"
DASHBOARDS_APPLY="${EJ_DASHBOARDS_APPLY:-$REPO/deploy/dashboards/apply.sh}"
SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
AGE_KEY="${EJ_AGE_KEY:-$HOME/.config/sops/age/event-junkie.txt}"
WG_DIR="${EJ_WG_DIR:-$HOME/.wireguard}"
BOOT_TRIES="${EJ_BOOT_TRIES:-90}"
BOOT_SLEEP="${EJ_BOOT_SLEEP:-10}"
REPO_SLUG=enorm-labs/event-junkie
APP_HR=event-junkie

ENV="${1:-}"
case "$ENV" in
    staging)
        TUNNEL_IP=10.10.1.1
        DB_IP=10.1.1.10
        DEDICATED_DB=0
        SITE=staging.event-junkie.de
        ;;
    production)
        TUNNEL_IP=10.10.0.1
        DB_IP=10.0.1.21
        DEDICATED_DB=1
        SITE=event-junkie.de
        ;;
    *) die "expected staging or production, got '${ENV}'" ;;
esac
shift
NEXT=0
FROM=""
while [ $# -gt 0 ]; do
    case "$1" in
        --next)
            NEXT=1
            shift
            ;;
        --from)
            [ $# -ge 2 ] || die "--from needs a step"
            FROM="$2"
            shift 2
            ;;
        *) die "unknown argument '$1'" ;;
    esac
done

if [ "$NEXT" = 1 ]; then
    STEPS="boot tls access secrets flux ready verify tunnel"
    CTX="event-junkie-$ENV-next"
    NEXT_KNOWN_HOSTS="$HOME/.ssh/known_hosts.$ENV-next"
else
    STEPS="boot access database secrets backups flux ready alerts verify"
    CTX="event-junkie-$ENV"
fi
if [ -n "$FROM" ] && [[ " $STEPS " != *" $FROM "* ]]; then
    die "no step '$FROM' here; the steps are: $STEPS"
fi

unset KUBECONFIG
umask 077
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

log() { echo "rebuild-node: $CURRENT: $*"; }
k() { kubectl --context "$CTX" "$@"; }
kc() { security find-generic-password -s "$1" -w 2>/dev/null | tr -d '\n'; }
kc_value() {
    local v
    v="$(kc "$1")"
    [ -n "$v" ] || die "no Keychain item $1 (docs/CREDENTIALS.md § What is in the macOS Keychain)"
    printf '%s' "$v"
}
# SQL string literal: a quote is doubled. The value reaches psql on stdin, never as an argument.
sql_quote() {
    local q="'"
    printf "'%s'" "${1//$q/$q$q}"
}

# --- How each node is reached ---------------------------------------------------------------------

# The second pair's addresses, from the state.
if [ "$NEXT" = 1 ]; then
    if [ -n "${EJ_NEXT_PAIR_JSON:-}" ]; then
        next_json="$EJ_NEXT_PAIR_JSON"
    else
        next_json="$(direnv exec "$REPO/infra" tofu -chdir="$REPO/infra/environments/$ENV" output -json next_pair 2>/dev/null)" || next_json=null
    fi
    NEXT_TEMP_IP="$(jq -r '.k3s_temporary_ipv4 // empty' <<<"$next_json")"
    NEXT_K3S_IP="$(jq -r '.k3s_private_ip // empty' <<<"$next_json")"
    NEXT_DB_IP="$(jq -r '.postgres_private_ip // empty' <<<"$next_json")"
    [ -n "$NEXT_TEMP_IP" ] && [ -n "$NEXT_K3S_IP" ] ||
        die "the state holds no next k3s node: order it first (infra/order-next-pair.sh $ENV)"
fi

base_ssh=(ssh -i "$SSH_KEY" -o BatchMode=yes -o ConnectTimeout=5)
# A new node, through the running k3s node over the private network. -o options reach the target
# only, so the jump keeps checking the running node against ~/.ssh/known_hosts.
via_running() {
    local host="$1"
    shift
    "${base_ssh[@]}" -o UserKnownHostsFile="$NEXT_KNOWN_HOSTS" -o StrictHostKeyChecking=accept-new \
        -J "ops@$TUNNEL_IP" "ops@$host" "$@"
}
# A node rebuilt in place, before the tunnel knows its key: on the public IPv4, which needs
# `admin_cidrs` open. Its host key is new, so it is accepted into a throwaway file.
public_ip() { awk '/^[[:space:]]*Endpoint/ { sub(/^[^=]*=[[:space:]]*/, ""); sub(/:[0-9]+[[:space:]]*$/, ""); print; exit }' "$WG_DIR/$ENV.conf"; }
via_public() {
    local host="$1" ip
    shift
    ip="$(public_ip)"
    [ -n "$ip" ] || die "no Endpoint in $WG_DIR/$ENV.conf"
    local -a jump=()
    [ "$host" = k3s ] || jump=(-J "ops@$ip")
    [ "$host" != k3s ] || host="$ip"
    "${base_ssh[@]}" -o UserKnownHostsFile="$WORK/known_hosts" -o StrictHostKeyChecking=accept-new ${jump[@]+"${jump[@]}"} "ops@$host" "$@"
}
# The database, once the tunnel works: on the k3s node in staging, through it in production.
db_ssh() {
    if [ "$DEDICATED_DB" = 1 ]; then
        "${base_ssh[@]}" -J "ops@$TUNNEL_IP" "ops@$DB_IP" "$@"
    else
        "${base_ssh[@]}" "ops@$TUNNEL_IP" "$@"
    fi
}

iface_up() { [ -e "${EJ_WG_RUN_DIR:-/var/run/wireguard}/$1.name" ]; }

# --- Steps ----------------------------------------------------------------------------------------

# wait_boot <name> <command that runs its arguments on the node…>: waits for the result cloud-init's last step writes.
wait_boot() {
    local name="$1" try out
    shift
    for try in $(seq 1 "$BOOT_TRIES"); do
        out="$("$@" sudo sh -c "'grep -h -e \"boot: every step succeeded\" -e \"boot: failed\" /var/log/cloud-init-output.log | tail -1; tail -1 /var/log/event-junkie/harden.log'" 2>/dev/null || true)"
        case "$out" in
            *"boot: failed"*) die "$name: $(grep 'boot:' <<<"$out"). Each script's log is /var/log/event-junkie/<name>.log" ;;
            *"boot: every step succeeded"*"harden: done"*)
                log "$name: every step succeeded, harden: done"
                return 0
                ;;
        esac
        if [ "$try" -lt "$BOOT_TRIES" ]; then sleep "$BOOT_SLEEP"; fi
    done
    local hint="with admin_cidrs open for this Mac"
    [ "$NEXT" = 0 ] || hint="through ops@$TUNNEL_IP, with the running tunnel up"
    die "$name: no boot result after $((BOOT_TRIES * BOOT_SLEEP))s. Is SSH reachable $hint?"
}

step_boot() {
    if [ "$NEXT" = 1 ]; then
        wait_boot "$ENV-k3s-next" via_running "$NEXT_K3S_IP"
        [ -z "$NEXT_DB_IP" ] || wait_boot "$ENV-postgres-next" via_running "$NEXT_DB_IP"
    else
        [ -f "$WG_DIR/$ENV.conf" ] || die "no $WG_DIR/$ENV.conf, whose Endpoint is the node's public IPv4"
        wait_boot "$ENV-k3s" via_public k3s
        [ "$DEDICATED_DB" = 0 ] || wait_boot "$ENV-postgres" via_public "$DB_IP"
    fi
}

TLS_FILE="$WORK/tls.json"
step_tls() {
    local name raw items=()
    for name in event-junkie-tls event-junkie-tls-redirect; do
        raw="$(kubectl --context "event-junkie-$ENV" get secret "$name" -n event-junkie -o json --ignore-not-found)" ||
            die "cannot read $name from the running cluster: is $WG_DIR/$ENV.conf up?"
        [ -z "$raw" ] || items+=("$raw")
    done
    [ "${#items[@]}" -gt 0 ] || die "the running cluster holds no event-junkie-tls Secret"
    printf '%s\n' "${items[@]}" | jq -s '{apiVersion: "v1", kind: "List", items: [.[] | {apiVersion, kind, type, data,
        metadata: {name: .metadata.name, namespace: .metadata.namespace, labels: (.metadata.labels // {}),
            annotations: (.metadata.annotations // {} | with_entries(select(.key | startswith("cert-manager.io/"))))}}]}' >"$TLS_FILE"
    log "read $(jq '.items | length' "$TLS_FILE") certificate Secret(s) from event-junkie-$ENV; they are applied in step flux"
}

step_access() {
    if [ "$NEXT" = 1 ]; then
        local args=(--next --endpoint "$NEXT_TEMP_IP" --merge) key=""
        [ -z "$NEXT_DB_IP" ] || args+=(--db-node "$NEXT_DB_IP")
        if ! iface_up "$ENV-next"; then
            key="$(via_running "$NEXT_K3S_IP" sudo cat /etc/wireguard/public.key | tr -d '[:space:]')" ||
                die "cannot read the new node's WireGuard key through ops@$TUNNEL_IP"
            args+=(--server-key "$key")
        fi
        "$CLUSTER_ACCESS" "$ENV" "${args[@]}"
    else
        "$CLUSTER_ACCESS" "$ENV" --merge
    fi
    k get nodes >/dev/null || die "$CTX does not answer"
    log "$CTX answers"
}

step_database() {
    local pw sql
    pw="$(SOPS_AGE_KEY_FILE="$AGE_KEY" sops --decrypt "$REPO/deploy/clusters/$ENV/secrets/events-db.yaml" | yq '.data.password' | base64 -d)" ||
        die "cannot decrypt events-db with $AGE_KEY"
    if [ "$(printf '%s\n' "$pw" | db_ssh "read -r p; PGPASSWORD=\"\$p\" psql -h $DB_IP -U events -d events -tAc 'SELECT 1'" 2>/dev/null)" = 1 ]; then
        log "events: the role matches events-db"
    else
        printf 'ALTER ROLE events PASSWORD %s;\n' "$(sql_quote "$pw")" | db_ssh 'sudo -u postgres psql -v ON_ERROR_STOP=1 -q' ||
            die "cannot set the events password"
        log "events: password set from events-db"
    fi
    sql="$(printf 'ALTER ROLE metrics PASSWORD %s;\nALTER ROLE bff PASSWORD %s;\n' \
        "$(sql_quote "$(kc_value "event-junkie-$ENV-metrics-password")")" "$(sql_quote "$(kc_value "event-junkie-$ENV-bff-password")")")"
    printf '%s\n' "$sql" | db_ssh 'sudo -u postgres psql -v ON_ERROR_STOP=1 -q' || die "cannot set the metrics and bff passwords"
    log "metrics, bff: passwords set from the Keychain"
}

step_secrets() {
    k create namespace flux-system --dry-run=client -o yaml | k apply -f - >/dev/null
    local skip=""
    [ "$NEXT" = 0 ] || skip="flux-system/github-dispatch"
    EJ_KUBE_CONTEXT="$CTX" EJ_SKIP_SECRETS="$skip" "$CLUSTER_SECRETS" "$ENV"
    EJ_KUBE_CONTEXT="$CTX" EJ_SKIP_SECRETS="$skip" "$CLUSTER_SECRETS" "$ENV" --check
}

step_backups() {
    local creds
    creds="$(printf 'AWS_ACCESS_KEY_ID=%s\nAWS_SECRET_ACCESS_KEY=%s\nHEALTHCHECK_URL=%s\n' \
        "$(kc_value event-junkie-s3-access-key)" "$(kc_value event-junkie-s3-secret-key)" "$(kc_value "event-junkie-$ENV-healthcheck-url")")"
    printf '%s\n' "$creds" | db_ssh 'sudo install -d -m 0750 -o root -g postgres /etc/wal-g &&
        sudo tee /etc/wal-g/credentials.env >/dev/null && sudo chmod 0640 /etc/wal-g/credentials.env &&
        sudo chgrp postgres /etc/wal-g/credentials.env' || die "cannot write /etc/wal-g/credentials.env"
    log "/etc/wal-g/credentials.env written"
    if db_ssh 'sudo -u postgres walg check' >/dev/null 2>&1; then
        log "walg check passes, no new base backup"
        return 0
    fi
    db_ssh 'sudo systemctl start walg-basebackup && sudo -u postgres walg check' || die "the first base backup or walg check failed"
    log "base backup taken, walg check passes"
}

step_flux() {
    local dir="$REPO/deploy/clusters/$ENV" title pub keys id
    k create namespace flux-system --dry-run=client -o yaml | k apply -f - >/dev/null
    if ! k get secret flux-system -n flux-system >/dev/null 2>&1; then
        flux --context "$CTX" create secret git flux-system -n flux-system --url="ssh://git@github.com/$REPO_SLUG" >/dev/null
        log "flux-system: new deploy key generated"
    fi
    pub="$(k get secret flux-system -n flux-system -o jsonpath='{.data.identity\.pub}' | base64 -d | awk '{ print $1 " " $2 }')"
    [ -n "$pub" ] || die "flux-system holds no identity.pub"
    title="flux-system-main-flux-system-./deploy/clusters/$ENV"
    [ "$NEXT" = 0 ] || title="$title (next)"
    keys="$(gh api "repos/$REPO_SLUG/keys")"
    if jq -e --arg k "$pub" 'any(.[]; (.key | split(" ")[0:2] | join(" ")) == $k)' <<<"$keys" >/dev/null; then
        log "deploy key already on $REPO_SLUG"
    else
        printf '%s\n' "$pub" >"$WORK/deploy-key.pub"
        gh repo deploy-key add "$WORK/deploy-key.pub" --title "$title" -R "$REPO_SLUG" >/dev/null
        log "deploy key added, read-only: $title"
    fi
    # The running cluster keeps its key until the cutover; in place, the old key belonged to the replaced node.
    if [ "$NEXT" = 0 ]; then
        for id in $(jq -r --arg t "$title" --arg k "$pub" '.[] | select(.title == $t and ((.key | split(" ")[0:2] | join(" ")) != $k)) | .id' <<<"$keys"); do
            gh api -X DELETE "repos/$REPO_SLUG/keys/$id" >/dev/null
            log "old deploy key $id deleted"
        done
    fi

    if [ "$NEXT" = 1 ]; then
        k apply --server-side -f "$dir/flux-system/gotk-components.yaml" >/dev/null
        k wait --for=condition=Established crd/helmreleases.helm.toolkit.fluxcd.io --timeout=120s >/dev/null
        yq '.spec.suspend = true' "$dir/helm-release.yaml" | k apply --server-side --field-manager=rebuild-node -f - >/dev/null
        log "HelmRelease $APP_HR created suspended, before Flux applies it"
        if [ -s "$TLS_FILE" ]; then
            k create namespace event-junkie --dry-run=client -o yaml | k apply -f - >/dev/null
            k apply --server-side --field-manager=rebuild-node -f "$TLS_FILE" >/dev/null
            log "certificate Secret(s) copied from the running cluster"
        elif ! k get secret event-junkie-tls -n event-junkie >/dev/null 2>&1; then
            die "no certificate to copy: run from step tls, in the same invocation"
        fi
    fi
    k apply --server-side -k "$dir/flux-system" >/dev/null
    k wait --for=condition=Ready kustomization/flux-system -n flux-system --timeout=10m >/dev/null ||
        die "the flux-system Kustomization is not ready: flux --context $CTX get kustomizations"
    log "Flux reconciles $dir"
    if [ "$NEXT" = 1 ] && [ "$(k get helmrelease "$APP_HR" -n flux-system -o jsonpath='{.spec.suspend}')" != true ]; then
        flux --context "$CTX" suspend helmrelease "$APP_HR" -n flux-system >/dev/null || true
        die "Flux took over spec.suspend on $APP_HR. It is suspended again; check that no BFF or importer pod ran"
    fi
}

step_ready() {
    local hr
    for hr in $(k get helmreleases -n flux-system -o jsonpath='{range .items[*]}{.metadata.name}{"\n"}{end}'); do
        if [ "$NEXT" = 1 ] && [ "$hr" = "$APP_HR" ]; then
            log "$hr: suspended until the cutover"
            continue
        fi
        k wait --for=condition=Ready "helmrelease/$hr" -n flux-system --timeout=15m >/dev/null ||
            die "$hr is not ready: flux --context $CTX get helmreleases -A"
        log "$hr: ready"
    done
}

step_alerts() {
    EJ_NODE="ops@$TUNNEL_IP" "$ALERTS_APPLY"
    EJ_NODE="ops@$TUNNEL_IP" "$DASHBOARDS_APPLY"
    # Report only: a rule over a stream that has no data yet reads NO DATA on a fresh cluster (#4130).
    EJ_NODE="ops@$TUNNEL_IP" "$ALERTS_APPLY" --check || log "alerts --check reported rules that cannot fire yet"
}

step_verify() {
    local bad
    k get nodes --no-headers | awk '$2 != "Ready" { bad = 1 } END { exit bad }' || die "a node is not Ready"
    bad="$(k get pods -A -o jsonpath='{range .items[*]}{range .status.containerStatuses[*]}{.state.waiting.reason}{"\n"}{end}{end}' | grep -c CrashLoopBackOff || true)"
    [ "$bad" = 0 ] || die "$bad container(s) in CrashLoopBackOff: kubectl --context $CTX get pods -A"
    log "nodes Ready, no container in CrashLoopBackOff"
    if [ "$NEXT" = 1 ]; then
        [ "$(k get helmrelease "$APP_HR" -n flux-system -o jsonpath='{.spec.suspend}')" = true ] || die "$APP_HR is not suspended"
        log "$APP_HR still suspended; the cutover starts it"
        return 0
    fi
    k get certificate -A --no-headers | awk '$3 != "True" { bad = 1 } END { exit bad }' || die "a certificate is not ready: kubectl --context $CTX get certificate -A"
    local resolve=()
    [ "$ENV" = production ] || resolve=(--resolve "$SITE:443:$TUNNEL_IP")
    curl -fsS -o /dev/null ${resolve[@]+"${resolve[@]}"} "https://$SITE/" || die "https://$SITE/ does not answer 2xx"
    curl -fsS -o /dev/null ${resolve[@]+"${resolve[@]}"} "https://$SITE/api/events?size=1" || die "https://$SITE/api/events does not answer 2xx"
    db_ssh 'sudo -u postgres walg check' >/dev/null || die "walg check fails"
    log "https://$SITE/ and /api/events answer, certificates ready, walg check passes"
}

step_tunnel() {
    sudo wg-quick down "$WG_DIR/$ENV-next.conf" >/dev/null 2>&1 || true
    sudo wg-quick up "$WG_DIR/$ENV.conf" >/dev/null
    log "the running tunnel is back up. The new cluster is $CTX over $WG_DIR/$ENV-next.conf, one tunnel at a time"
}

started=0
[ -n "$FROM" ] || started=1
for CURRENT in $STEPS; do
    [ "$CURRENT" = "$FROM" ] && started=1
    [ "$started" = 1 ] || continue
    "step_$CURRENT"
done
CURRENT="done"
log "$ENV$([ "$NEXT" = 0 ] || echo ' next pair'): every step passed"
