#!/usr/bin/env bash
#
# apply.sh — plan an OpenTofu stack to a file, refuse an unasked replacement, and apply exactly that file (#4132).
#
# Usage:
#   infra/apply.sh <bootstrap|staging|production> [--allow-replace] [--yes-if-only-creates <regex>] [tofu plan flags…]
#
#   --allow-replace   permit a replace or destroy of an hcloud_server, hcloud_volume, hcloud_primary_ip
#                     or hcloud_zone. With a server among them, check-capacity.sh --probe runs first
#   --yes-if-only-creates <regex>
#                     apply without the prompt when every change is a create of an address matching
#                     <regex>. Any other plan exits 3 and applies nothing. order-next-pair.sh uses it
#
#   infra/apply.sh production -target=hcloud_zone_rrset.redirect
#   infra/apply.sh staging --allow-replace -replace=module.environment.hcloud_server.k3s -var "admin_cidrs=$ADMIN"
#
# A checked plan and the applied plan were once two runs: the plan had `-target`, the bare
# `tofu apply` after it planned again without it and replaced both production nodes (#4125). Here
# the plan file is the only thing applied, and it lives in a 0700 temp dir removed on exit, because
# it holds every variable in clear text. `-var` and `-var-file` go to the apply as well, so a value
# that differs from terraform.tfvars passes OpenTofu's check that the two match.
#
# The plan is read from `tofu show -json`, never from its text, so a reworded release cannot
# silence the refusal. `No changes` exits 0 without a prompt.
#
# Requires: tofu, jq, and direnv with infra/.envrc allowed (it holds the credentials).

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    echo "apply: $*" >&2
    exit 1
}

INFRA="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CAPACITY="${EJ_CHECK_CAPACITY:-$INFRA/check-capacity.sh}"
GUARDED='["hcloud_server","hcloud_volume","hcloud_primary_ip","hcloud_zone"]'

STACK="${1:-}"
case "$STACK" in
    bootstrap) DIR="$INFRA/bootstrap" ;;
    staging | production) DIR="$INFRA/environments/$STACK" ;;
    *) die "expected bootstrap, staging or production, got '${STACK}'" ;;
esac
shift

ALLOW_REPLACE=0
CREATES_ONLY=""
PLAN_ARGS=()
VAR_ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --allow-replace) ALLOW_REPLACE=1 ;;
        --yes-if-only-creates)
            [ $# -ge 2 ] || die "$1 needs a regex"
            CREATES_ONLY="$2"
            shift
            ;;
        -var | -var-file)
            [ $# -ge 2 ] || die "$1 needs a value"
            PLAN_ARGS+=("$1" "$2")
            VAR_ARGS+=("$1" "$2")
            shift
            ;;
        -var=* | -var-file=*)
            PLAN_ARGS+=("$1")
            VAR_ARGS+=("$1")
            ;;
        -out | -out=*) die "the plan file is this script's own; drop '$1'" ;;
        *) PLAN_ARGS+=("$1") ;;
    esac
    shift
done

command -v jq >/dev/null || die "needs jq"
command -v direnv >/dev/null || die "needs direnv, which loads the credentials from infra/.envrc"

tf() { direnv exec "$DIR" tofu -chdir="$DIR" "$@"; }

umask 077
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
PLAN="$WORK/tfplan"

# The `+` form keeps an empty array from tripping `set -u` in macOS's bash 3.2.
tf plan -input=false -out="$PLAN" ${PLAN_ARGS[@]+"${PLAN_ARGS[@]}"}
SHOW="$WORK/plan.json"
tf show -json "$PLAN" >"$SHOW"

changes="$(jq '[.resource_changes[]?, (.output_changes // {} | .[]) | .change.actions // .actions
    | select(. != ["no-op"] and . != ["read"])] | length' "$SHOW")"
if [ "$changes" -eq 0 ]; then
    echo "apply: No changes in ${STACK}. Nothing to apply."
    exit 0
fi

echo
jq -r '[.resource_changes[]? | .change.actions] |
    "apply: \(map(select(index("create"))) | length) to add, \(map(select(. == ["update"])) | length) to change, \(map(select(index("delete"))) | length) to destroy"' "$SHOW"

# A replace is `["delete","create"]` or `["create","delete"]`; a destroy is `["delete"]`.
removals="$(jq -r '.resource_changes[]? | select(.mode == "managed" and (.change.actions | index("delete")))
    | "\(if (.change.actions | length) > 1 then "replace" else "destroy" end)\t\(.type)\t\(.address)"' "$SHOW")"
guarded=""
if [ -n "$removals" ]; then
    echo "apply: it would replace or destroy:"
    while IFS=$'\t' read -r action type address; do
        printf '  %-8s %s\n' "$action" "$address"
        if jq -e --arg t "$type" 'index($t)' <<<"$GUARDED" >/dev/null; then
            guarded="${guarded}${type} "
        fi
    done <<<"$removals"
fi

if [ -n "$guarded" ]; then
    if [ "$ALLOW_REPLACE" -ne 1 ]; then
        die "refused: the plan replaces or destroys ${guarded% }. Nothing was applied. A rebuild is asked for with --allow-replace (CLUSTER_BOOTSTRAP.md § Rebuilding a node)."
    fi
    case " $guarded" in
        *" hcloud_server "*)
            [ "$STACK" != bootstrap ] || die "bootstrap has no servers, so a server here is unexpected; stop and read the plan"
            echo "apply: a server is replaced, so first: can Hetzner place it now?"
            direnv exec "$DIR" "$CAPACITY" --probe "$STACK" || die "check-capacity.sh says it cannot be placed now. Nothing was applied."
            ;;
    esac
fi

if [ -n "$CREATES_ONLY" ]; then
    others="$(jq -r --arg re "$CREATES_ONLY" '[.resource_changes[]?
        | select(.change.actions != ["no-op"] and .change.actions != ["read"])
        | select(.change.actions != ["create"] or (.address | test($re) | not)) | .address] | join(" ")' "$SHOW")"
    if [ -n "$others" ]; then
        echo "apply: the plan holds more than creates matching ${CREATES_ONLY}: ${others}. Nothing was applied; it needs a person." >&2
        exit 3
    fi
    echo "apply: every change is a create matching ${CREATES_ONLY}, so no prompt."
else
    printf 'apply: Apply exactly this plan to %s? Type yes: ' "$STACK"
    answer=""
    read -r answer || true
    [ "$answer" = yes ] || die "not applied"
fi

tf apply -input=false ${VAR_ARGS[@]+"${VAR_ARGS[@]}"} "$PLAN"
