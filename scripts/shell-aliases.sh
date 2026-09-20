#!/usr/bin/env bash
#
# Shell functions for day-to-day work against the two clusters.
#
#   echo 'source ~/repos/event-junkie/scripts/shell-aliases.sh' >> ~/.zshrc
#
# A file rather than a cheatsheet block, because this drifts loudly: reviewed in PRs, ShellCheck in
# `pre-commit`, a wrong path fails in your terminal. **Nothing here wraps `tofu`, `helm upgrade` or
# anything that writes to production** — those want the friction (infra/AGENTS.md, deploy/AGENTS.md).
# Functions rather than aliases, so arguments pass through. Counterpart: docs/ops/DAILY_COMMANDS.md.

# shellcheck shell=bash

EJ_SSH_KEY="${EJ_SSH_KEY:-$HOME/.ssh/id_ed25519_hetzner}"
# `${BASH_SOURCE[0]}` is empty when sourced by zsh, so the path is a variable with the documented
# clone as default.
EJ_REPO="${EJ_REPO:-$HOME/repos/event-junkie}"
EJ_STAGING="${EJ_STAGING:-10.10.1.1}"
EJ_PRODUCTION="${EJ_PRODUCTION:-10.10.0.1}"
EJ_PRODUCTION_DB="${EJ_PRODUCTION_DB:-10.0.1.20}"

# --- the session --------------------------------------------------------------------------------
# Short names for scripts/ej.sh, so there is one copy of the mechanics.

ej-up() { "$EJ_REPO/scripts/ej.sh" up staging; }
ej-up-prod() { "$EJ_REPO/scripts/ej.sh" up production; }
ej-down() { "$EJ_REPO/scripts/ej.sh" down staging "$@"; }
ej-down-prod() { "$EJ_REPO/scripts/ej.sh" down production "$@"; }
ej-status() { "$EJ_REPO/scripts/ej.sh" status; }
ej-versions() { "$EJ_REPO/scripts/ej.sh" versions; }

# --- cluster ----------------------------------------------------------------------------------
# `--context` pinned: both clusters live in one kubeconfig.

ejk() { kubectl --context event-junkie-staging "$@"; }
ejkp() { kubectl --context event-junkie-production "$@"; }
ejf() { flux --context event-junkie-staging "$@"; }
ejfp() { flux --context event-junkie-production "$@"; }
ej9() { k9s --context event-junkie-staging "$@"; }
ej9p() { k9s --context event-junkie-production "$@"; }

# --- the site ---------------------------------------------------------------------------------
# `-k` is correct: staging issues from Let's Encrypt's *staging* CA. --resolve rather than
# /etc/hosts, so nothing is left behind.

ej-site() {
    curl -sS -k --max-time 20 --resolve "staging.event-junkie.de:443:${EJ_STAGING}" \
        "https://staging.event-junkie.de${1:-/}" -o /dev/null \
        -w 'staging %{http_code} in %{time_total}s\n'
}

ej-api() {
    curl -sS -k --max-time 20 --resolve "staging.event-junkie.de:443:${EJ_STAGING}" \
        "https://staging.event-junkie.de/api/${1:-events?size=1}"
}

# --- one venue, end to end ---------------------------------------------------------------------
# Answered from the APIs rather than psql: the admin API has the source row, and the public API says
# whether the events reached what a visitor sees. The importer is deliberately unroutable, so its
# half needs a port-forward (node-originated traffic is not subject to NetworkPolicy in k3s); the
# site's half goes through the ingress on purpose.

ej-venue() {
    local slug="${1:?usage: ej-venue <slug>}"
    printf '=== source row (importer admin API) ===\n'
    kubectl --context event-junkie-staging -n event-junkie port-forward svc/event-junkie-importer 8081:8081 >/dev/null 2>&1 &
    local pf=$!
    sleep 3
    # The error body carries a `status` field of its own, which would render as a very broken venue
    # rather than a typo. Check the code instead.
    local code
    code=$(curl -sS -o /tmp/ej-venue.json -w '%{http_code}' --max-time 20 \
        "http://localhost:8081/api/admin/event-sources/${slug}")
    if [ "$code" = "200" ]; then
        python3 -c 'import json,sys; d=json.load(open("/tmp/ej-venue.json")); print(json.dumps({k: d.get(k) for k in ("slug","status","retryCount","lastImportAt","lastSuccessAt","lastEventCount","lastError")}, indent=2))'
    else
        printf 'no source with slug "%s" (HTTP %s)\n' "$slug" "$code"
    fi
    rm -f /tmp/ej-venue.json
    kill "$pf" 2>/dev/null
    printf '\n=== what the site would serve ===\n'
    ej-api "events?venue=${slug}&size=3" |
        python3 -c 'import json,sys; d=json.load(sys.stdin); print("future events:", d.get("totalElements")); [print(" ", e.get("eventDate"), (e.get("title") or "")[:60]) for e in (d.get("content") or [])]'
}

# --- database ---------------------------------------------------------------------------------
# Opens the forward, runs psql, closes the forward — an -f -N ssh left running is what holds port
# 15432 three days later.

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

# A superuser shell for anything CREATE ROLE-shaped; the forwards above connect as `events`.
ej-psql-super() { ssh -i "$EJ_SSH_KEY" "ops@${EJ_STAGING}" 'sudo -u postgres psql -d events'; }
ej-psql-super-prod() {
    ssh -i "$EJ_SSH_KEY" -J "ops@${EJ_PRODUCTION}" "ops@${EJ_PRODUCTION_DB}" 'sudo -u postgres psql -d events'
}

# --- backups ------------------------------------------------------------------------------------

# `walg check`, not `systemctl status`: the timers can be green while every archive fails.
ej-backups() { ssh -i "$EJ_SSH_KEY" "ops@${EJ_STAGING}" 'sudo -u postgres walg check'; }
ej-backups-prod() {
    ssh -i "$EJ_SSH_KEY" -J "ops@${EJ_PRODUCTION}" "ops@${EJ_PRODUCTION_DB}" 'sudo -u postgres walg check'
}
