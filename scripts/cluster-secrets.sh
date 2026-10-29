#!/usr/bin/env bash
#
# cluster-secrets.sh — create every hand-made Secret of a cluster from the Keychain, or check them (#4133).
#
# Usage:
#   scripts/cluster-secrets.sh <staging|production>            create or update all of them
#   scripts/cluster-secrets.sh <staging|production> --check    compare the cluster with the Keychain; changes nothing
#   scripts/cluster-secrets.sh <staging|production> --import   store a value the Keychain lacks, read from the cluster
#
# The production rebuild restored fourteen objects by hand, and four values were in no Keychain item
# at all. The VAPID pair lived only in its Secret, so the rebuild ended every Web Push subscription.
# docs/ops/SECRETS.md is the list and says why each exists; this is that list as one idempotent run.
#
# A write first resolves every value, and a missing Keychain item stops it before anything is
# written. Values never reach an argument list or the terminal: each manifest is built in a 0700
# temp dir and piped to `kubectl apply --server-side`, which also writes no last-applied annotation,
# a second copy of the value. `--import` is the exception: `security add-generic-password -w` takes
# the value as an argument, visible to `ps` for that moment. It never overwrites an item.
#
# `flux-system` exists only after `flux bootstrap` (CLUSTER_BOOTSTRAP.md §9). Without it the
# Secrets there are skipped, and a second run after §9 adds them. The database roles `metrics` and
# `bff` are `psql` by hand first (SECRETS.md). `events-db` is not here: Flux restores it from git.
#
# EJ_KUBE_CONTEXT names another context, such as event-junkie-<env>-next for the second pair of
# #4128. EJ_SKIP_SECRETS lists `<namespace>/<name>` Secrets to leave out, which rebuild-node.sh
# uses so the second pair records no deployment before the cutover.
#
# Requires: kubectl with the context event-junkie-<env>, jq, security (macOS), base64,
# and ~/.config/sops/age/event-junkie.txt.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    echo "cluster-secrets: $*" >&2
    exit 1
}

ENV="${1:-}"
case "$ENV" in
    staging) DB_HOST=10.1.1.10 ;;
    production) DB_HOST=10.0.1.20 ;;
    *) die "expected staging or production, got '${ENV}'" ;;
esac
MODE="${2:-write}"
case "$MODE" in
    write | --check | --import) ;;
    *) die "unknown argument '$MODE'" ;;
esac
[ $# -le 2 ] || die "too many arguments"

CTX="${EJ_KUBE_CONTEXT:-event-junkie-$ENV}"
AGE_KEY="${EJ_AGE_KEY:-$HOME/.config/sops/age/event-junkie.txt}"
O2_ROOT_EMAIL=hello@event-junkie.de
command -v jq >/dev/null || die "needs jq"
command -v security >/dev/null || die "needs the macOS security tool"

k() { kubectl --context "$CTX" "$@"; }
b64() { base64 | tr -d '\n'; }
b64_len() { printf '%s' "$1" | base64 -d | wc -c | tr -d ' '; }

# One line per key: <namespace> <secret> <key> <source>. A source is kc:<Keychain item>, file:<path>,
# const:<value>, or derived:<name>, which reads other Keychain items.
spec() {
    local ns
    if [ "$ENV" = staging ]; then
        echo "cert-manager hetzner token kc:event-junkie-hcloud-token"
    fi
    echo "flux-system github-dispatch token kc:event-junkie-$ENV-github-dispatch"
    echo "flux-system sops-age age.agekey file:$AGE_KEY"
    # The chart reads the flux-system copy through valuesFrom and the observability one directly (SECRETS.md).
    for ns in flux-system observability; do
        echo "$ns openobserve-credentials ZO_ROOT_USER_EMAIL const:$O2_ROOT_EMAIL"
        echo "$ns openobserve-credentials ZO_ROOT_USER_PASSWORD kc:event-junkie-$ENV-o2-root-password"
        echo "$ns openobserve-credentials ZO_S3_ACCESS_KEY kc:event-junkie-o2-access-key"
        echo "$ns openobserve-credentials ZO_S3_SECRET_KEY kc:event-junkie-o2-secret-key"
    done
    # The header is read only from flux-system: the collector's valuesFrom and the deploy/*/apply.sh scripts.
    echo "flux-system openobserve-credentials O2_BASIC_AUTH_HEADER derived:o2-header"
    echo "observability postgres-exporter DATA_SOURCE_NAME derived:metrics-dsn"
    echo "observability openobserve-smtp ZO_SMTP_PASSWORD kc:event-junkie-alerts-smtp-password"
    echo "event-junkie events-bff-db username const:bff"
    echo "event-junkie events-bff-db password kc:event-junkie-$ENV-bff-password"
    echo "event-junkie event-junkie-mail username kc:event-junkie-scaleway-project-id"
    echo "event-junkie event-junkie-mail password kc:event-junkie-$ENV-tem-secret-key"
    echo "event-junkie event-junkie-push APP_PUSH_VAPID_PRIVATE_KEY kc:event-junkie-$ENV-vapid-private-key"
    echo "event-junkie event-junkie-push APP_PUSH_VAPID_PUBLIC_KEY kc:event-junkie-$ENV-vapid-public-key"
    echo "event-junkie event-junkie-images IMAGE_STORAGE_ACCESS_KEY kc:event-junkie-images-access-key"
    echo "event-junkie event-junkie-images IMAGE_STORAGE_SECRET_KEY kc:event-junkie-images-secret-key"
    echo "event-junkie event-junkie-imgproxy IMGPROXY_KEY kc:event-junkie-$ENV-imgproxy-key"
    echo "event-junkie event-junkie-imgproxy IMGPROXY_SALT kc:event-junkie-$ENV-imgproxy-salt"
    echo "event-junkie event-junkie-anthropic APP_ANTHROPIC_API_KEY kc:event-junkie-anthropic-api-key"
    echo "event-junkie event-junkie-discogs APP_DISCOGS_CONSUMER_KEY kc:discogs-consumer-key"
    echo "event-junkie event-junkie-discogs APP_DISCOGS_CONSUMER_SECRET kc:discogs-consumer-secret"
    if [ "$ENV" = production ]; then
        echo "event-junkie event-junkie-instagram APP_INSTAGRAM_ACCESS_TOKEN kc:instagram-token"
        # Production only: both clusters send from one Scaleway project, so one cluster reads its count (#4017).
        echo "event-junkie event-junkie-mail-quota api-key kc:event-junkie-tem-read-key"
        echo "event-junkie event-junkie-mail-quota project-id kc:event-junkie-scaleway-project-id"
    fi
}

# `-w` prints the value with a trailing newline, which a pasted --from-file once welded into a
# token (CLUSTER_BOOTSTRAP.md §8). Exit 1 when the item does not exist.
kc() { security find-generic-password -s "$1" -w 2>/dev/null | tr -d '\n'; }
kc_exists() { security find-generic-password -s "$1" >/dev/null 2>&1; }

# The base64 of a source's value on stdout; on a missing item, its name on stderr and exit 1.
resolve() {
    local source="$1" pw
    case "$source" in
        kc:*)
            kc_exists "${source#kc:}" || {
                echo "${source#kc:}" >&2
                return 1
            }
            kc "${source#kc:}" | b64
            ;;
        file:*)
            [ -f "${source#file:}" ] || {
                echo "${source#file:}" >&2
                return 1
            }
            b64 <"${source#file:}"
            ;;
        const:*) printf '%s' "${source#const:}" | b64 ;;
        derived:o2-header)
            kc_exists "event-junkie-$ENV-o2-root-password" || {
                echo "event-junkie-$ENV-o2-root-password" >&2
                return 1
            }
            pw="$(kc "event-junkie-$ENV-o2-root-password")"
            printf 'Basic %s' "$(printf '%s:%s' "$O2_ROOT_EMAIL" "$pw" | b64)" | b64
            ;;
        derived:metrics-dsn)
            kc_exists "event-junkie-$ENV-metrics-password" || {
                echo "event-junkie-$ENV-metrics-password" >&2
                return 1
            }
            pw="$(kc "event-junkie-$ENV-metrics-password")"
            # A URI: an unencoded `@` or `#` parses as something else (CLUSTER_BOOTSTRAP.md §8).
            pw="$(printf '%s' "$pw" | jq -sRr @uri)"
            printf 'postgresql://metrics:%s@%s:5432/events?sslmode=require' "$pw" "$DB_HOST" | b64
            ;;
        *) die "unknown source '$source'" ;;
    esac
}

umask 077
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
SPEC="$WORK/spec"
spec | awk -v skip=" ${EJ_SKIP_SECRETS:-} " 'index(skip, " " $1 "/" $2 " ") == 0' >"$SPEC"

flux_ready() { k get namespace flux-system >/dev/null 2>&1; }

# Every loop over the spec reads it on fd 3, so nothing inside can swallow the rest from stdin.

if [ "$MODE" = --import ]; then
    imported=0
    while read -r ns name key source <&3; do
        case "$source" in kc:*) ;; *) continue ;; esac
        item="${source#kc:}"
        kc_exists "$item" && continue
        value="$(k get secret "$name" -n "$ns" -o json 2>/dev/null | jq -r --arg k "$key" '.data[$k] // empty')" || value=""
        if [ -z "$value" ]; then
            echo "  $item: not in the Keychain, and $ns/$name has no $key to import"
            continue
        fi
        security add-generic-password -a "${USER:-$(id -un)}" -s "$item" -w "$(printf '%s' "$value" | base64 -d)"
        echo "  $item: imported from $ns/$name ($(b64_len "$value") bytes)"
        imported=$((imported + 1))
    done 3<"$SPEC"
    echo "cluster-secrets: imported $imported Keychain item(s) from $CTX. Copy each new one into KeePass too."
    exit 0
fi

if [ "$MODE" = --check ]; then
    problems=0
    while read -r ns name key source <&3; do
        cluster="$(k get secret "$name" -n "$ns" -o json 2>/dev/null | jq -r --arg k "$key" '.data[$k] // empty')" || cluster=""
        if ! local_value="$(resolve "$source" 2>/dev/null)"; then
            printf '  %-45s %-28s no Keychain value (%s)\n' "$ns/$name" "$key" "$source"
            problems=$((problems + 1))
        elif [ -z "$cluster" ]; then
            printf '  %-45s %-28s missing on the cluster\n' "$ns/$name" "$key"
            problems=$((problems + 1))
        elif [ "$cluster" = "$local_value" ]; then
            printf '  %-45s %-28s same (%s bytes)\n' "$ns/$name" "$key" "$(b64_len "$cluster")"
        else
            printf '  %-45s %-28s differs (cluster %s bytes, Keychain %s bytes)\n' "$ns/$name" "$key" \
                "$(b64_len "$cluster")" "$(b64_len "$local_value")"
            problems=$((problems + 1))
        fi
    done 3<"$SPEC"
    [ "$problems" -eq 0 ] || die "$problems key(s) differ or are missing on $CTX"
    echo "cluster-secrets: every key on $CTX matches the Keychain"
    exit 0
fi

# Write: resolve everything first, so a missing item stops the run with nothing applied.
missing=""
while read -r ns name key source <&3; do
    mkdir -p "$WORK/obj/$ns"
    if value="$(resolve "$source" 2>"$WORK/err")"; then
        printf '%s\t%s\n' "$key" "$value" >>"$WORK/obj/$ns/$name"
    else
        missing="$missing\n  $(cat "$WORK/err") (for $ns/$name $key)"
    fi
done 3<"$SPEC"
[ -z "$missing" ] || die "not in the Keychain, so nothing was applied:$(printf '%b' "$missing")"

apply() { k apply --server-side --force-conflicts --field-manager=cluster-secrets -f - >/dev/null; }

skipped=""
flux_ready || skipped="flux-system"
cut -d' ' -f1 "$SPEC" | sort -u | while read -r ns; do
    [ "$ns" != "$skipped" ] || continue
    jq -n --arg ns "$ns" '{apiVersion: "v1", kind: "Namespace", metadata: {name: $ns}}' | apply
done

echo "cluster-secrets: applied on $CTX:"
for file in "$WORK"/obj/*/*; do
    name="$(basename "$file")"
    ns="$(basename "$(dirname "$file")")"
    if [ "$ns" = "$skipped" ]; then
        echo "  $ns/$name: skipped, flux-system does not exist yet. Run again after flux bootstrap (CLUSTER_BOOTSTRAP.md §9)"
        continue
    fi
    jq -Rn --arg ns "$ns" --arg name "$name" '[inputs | split("\t") | {(.[0]): .[1]}] | add |
        {apiVersion: "v1", kind: "Secret", type: "Opaque", metadata: {name: $name, namespace: $ns}, data: .}' <"$file" | apply
    lengths=""
    while IFS=$'\t' read -r key value; do
        lengths="$lengths $key=$(b64_len "$value")"
    done <"$file"
    echo "  $ns/$name:$lengths"
done
