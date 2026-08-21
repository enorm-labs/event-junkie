#!/usr/bin/env bash
#
# Shell functions for day-to-day work against the two clusters.
#
#   echo 'source ~/repos/event-junkie/scripts/shell-aliases.sh' >> ~/.zshrc
#
# A file rather than a block pasted into a cheatsheet, because a cheatsheet drifts from reality
# silently and this drifts loudly: it is reviewed in PRs, ShellCheck runs over it in `pre-commit`,
# and a wrong path fails in your terminal instead of reading plausibly on a page.
#
# **Nothing here wraps `tofu`, `helm upgrade`, or anything that writes to production.** Those want
# the friction; see infra/AGENTS.md and deploy/AGENTS.md, both of which open with what must never be
# run on your own initiative.
#
# Functions rather than aliases throughout, so arguments pass through: `ejk get pods -A` works.
#
# The counterpart documentation is docs/ops/DAILY_COMMANDS.md.

# shellcheck shell=bash

EJ_SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
EJ_STAGING="${EJ_STAGING:-10.10.1.1}"
EJ_PRODUCTION="${EJ_PRODUCTION:-10.10.0.1}"
EJ_PRODUCTION_DB="${EJ_PRODUCTION_DB:-10.0.1.20}"

# --- tunnels ----------------------------------------------------------------------------------
#
# The handshake check is the point. `wg-quick up` succeeds, adds routes and reports nothing wrong
# when outbound UDP/51820 is blocked — which corporate and hotel networks do silently. WireGuard
# never replies to an unauthenticated packet, so there is nothing to see from either side.

_ej_tunnel_up() {
    local conf="$1" addr="$2" name="$3"
    sudo wg-quick up "$conf" || return 1
    local _attempt
    for _attempt in $(seq 1 10); do
        if ping -c 1 -W 2 "$addr" >/dev/null 2>&1; then
            echo "${name}: up (${addr} answers)"
            return 0
        fi
        sleep 1
    done
    echo "${name}: interface is up but ${addr} does not answer — check 'sudo wg show' for a handshake." >&2
    echo "            No handshake usually means outbound UDP/51820 is blocked, not a broken node." >&2
    return 1
}

ej-up() { _ej_tunnel_up "$HOME/.wireguard/staging.conf" "$EJ_STAGING" "staging"; }
ej-up-prod() { _ej_tunnel_up "$HOME/.wireguard/production.conf" "$EJ_PRODUCTION" "production"; }

ej-down() { sudo wg-quick down "$HOME/.wireguard/staging.conf"; }
ej-down-prod() { sudo wg-quick down "$HOME/.wireguard/production.conf"; }

# --- cluster ----------------------------------------------------------------------------------
#
# `--context` is pinned rather than relying on the current one. Both clusters live in the same
# kubeconfig, so "which cluster am I on" is otherwise a question you have to remember to ask.

ejk() { kubectl --context event-junkie-staging "$@"; }
ejkp() { kubectl --context event-junkie-production "$@"; }
ejf() { flux --context event-junkie-staging "$@"; }
ejfp() { flux --context event-junkie-production "$@"; }
ej9() { k9s --context event-junkie-staging "$@"; }
ej9p() { k9s --context event-junkie-production "$@"; }

# --- the site ---------------------------------------------------------------------------------
#
# `-k` is correct and must not be "fixed": staging issues from Let's Encrypt's *staging* CA so the
# production rate limit is not burned. --resolve rather than /etc/hosts, so nothing is left behind.

ej-site() {
    curl -sS -k --max-time 20 --resolve "staging.event-junkie.de:443:${EJ_STAGING}" \
        "https://staging.event-junkie.de${1:-/}" -o /dev/null \
        -w 'staging %{http_code} in %{time_total}s\n'
}

ej-api() {
    curl -sS -k --max-time 20 --resolve "staging.event-junkie.de:443:${EJ_STAGING}" \
        "https://staging.event-junkie.de/api/${1:-events?size=1}"
}

# --- database ---------------------------------------------------------------------------------
#
# Opens the forward, runs psql, and closes the forward again — an -f -N ssh left running is the
# thing you find three days later wondering what is holding port 15432.

_ej_psql() {
    local ctx="$1" jump="$2" target="$3" port="$4"
    local pw
    pw="$(kubectl --context "$ctx" get secret events-db -n event-junkie -o jsonpath='{.data.password}' | base64 -d)" || return 1
    ssh -f -N -i "$EJ_SSH_KEY" -L "${port}:${target}:5432" "ops@${jump}" || return 1
    PGPASSWORD="$pw" psql -h 127.0.0.1 -p "$port" -U events -d events
    local rc=$?
    pkill -f "ssh -f -N -i ${EJ_SSH_KEY} -L ${port}:${target}:5432" 2>/dev/null
    return $rc
}

ej-db() { _ej_psql event-junkie-staging "$EJ_STAGING" localhost 15432; }
ej-db-prod() { _ej_psql event-junkie-production "$EJ_PRODUCTION" "$EJ_PRODUCTION_DB" 15433; }

# A superuser shell, for anything CREATE ROLE-shaped. The forwards above connect as `events`,
# which cannot do it.
ej-psql-super() { ssh -i "$EJ_SSH_KEY" "ops@${EJ_STAGING}" 'sudo -u postgres psql -d events'; }
ej-psql-super-prod() {
    ssh -i "$EJ_SSH_KEY" -J "ops@${EJ_PRODUCTION}" "ops@${EJ_PRODUCTION_DB}" 'sudo -u postgres psql -d events'
}

# --- observability ----------------------------------------------------------------------------

ej-o2() {
    echo "OpenObserve on http://localhost:5080/ — root credentials from the password manager"
    ejk -n observability port-forward svc/openobserve-openobserve-standalone 5080:5080
}

# `walg check`, not `systemctl status`: the timers can be green while every archive fails.
ej-backups() { ssh -i "$EJ_SSH_KEY" "ops@${EJ_STAGING}" 'sudo -u postgres walg check'; }
ej-backups-prod() {
    ssh -i "$EJ_SSH_KEY" -J "ops@${EJ_PRODUCTION}" "ops@${EJ_PRODUCTION_DB}" 'sudo -u postgres walg check'
}

# --- one screen -------------------------------------------------------------------------------
#
# Deliberately reports "not Ready" rather than "all good": a listing that is empty when healthy is
# read in a second and cannot be mistaken for a stale success message.

ej-status() {
    local addr
    for addr in "$EJ_STAGING:staging" "$EJ_PRODUCTION:production"; do
        if ping -c 1 -W 2 "${addr%%:*}" >/dev/null 2>&1; then
            echo "tunnel ${addr##*:}: up"
        else
            echo "tunnel ${addr##*:}: DOWN"
        fi
    done
    local ctx
    for ctx in staging production; do
        printf 'cluster %-11s ' "$ctx"
        kubectl --context "event-junkie-${ctx}" --request-timeout=10s get nodes --no-headers 2>/dev/null \
            | awk '{print $1, $2}' || echo "unreachable"
    done
    # Parses the objects, not `flux get all`'s table. That table has a SUSPENDED column whose
    # healthy value is the string "False", so any "does this row contain False" test reports every
    # healthy resource as broken — which is what the first version of this did.
    #
    # Reports False and Unknown. Unknown matters: `--status-selector ready=false` omits it, and it
    # is what an in-progress install reports — and a stuck one.
    #
    # A resource with no Ready condition at all prints `<none>` and is deliberately ignored:
    # Alerts and Providers report that way until they have handled an event, and treating it as a
    # failure would make this noisy on every fresh cluster. The cost is that a genuinely broken
    # resource which has never reported is invisible here — `flux get all -A` is the fallback.
    #
    # Suspended resources are skipped. Production's application HelmRelease is deliberately
    # suspended until a chart release exists, and a status screen that always shows a problem is a
    # status screen nobody reads (#617 made that mistake with a dashboard threshold).
    echo "not ready:"
    local ctx2 out any=0
    for ctx2 in staging production; do
        out=$(kubectl --context "event-junkie-${ctx2}" --request-timeout=15s get \
            gitrepositories,ocirepositories,helmrepositories,helmcharts,helmreleases,kustomizations,alerts,providers \
            -A --no-headers \
            -o 'custom-columns=KIND:.kind,NAME:.metadata.name,SUSPEND:.spec.suspend,READY:.status.conditions[?(@.type=="Ready")].status' \
            2>/dev/null | awk -v ctx="$ctx2" '$3 != "true" && ($4 == "False" || $4 == "Unknown") { printf "  %s: %s/%s (%s)\n", ctx, $1, $2, $4 }')
        if [ -n "$out" ]; then
            any=1
            printf '%s\n' "$out"
        fi
    done
    [ "$any" -eq 0 ] && echo "  (nothing)"
}
