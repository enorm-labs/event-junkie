#!/usr/bin/env bash
#
# worklist.sh — the whole data-quality worklist of one environment, every issue and every page, in one JSON file.
#
# Usage:
#   scripts/worklist.sh                                  # production, to stdout
#   scripts/worklist.sh staging --out temp/worklist.json
#   scripts/worklist.sh production --summary             # one line per issue and source, the largest first
#
# The JSON is one object per issue, {"issue": …, "count": …, "entries": [ …WorklistEntryResponse… ]}. --summary prints
# `issue<TAB>source<TAB>count<TAB>flag kinds` instead, which is what /worklist reads first.
#
# Needs the forward scripts/ej.sh up <environment> opens (importer on 18081 for staging, 28081 for production);
# EJ_IMPORTER_URL overrides it. Reads only. The issue keys come from the endpoint's own refusal of an unknown key, so a
# new QualityIssue needs no change here.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

ENVIRONMENT=production
OUT=
SUMMARY=false

while [ $# -gt 0 ]; do
    case "$1" in
        staging | production) ENVIRONMENT="$1" ;;
        --summary) SUMMARY=true ;;
        --out)
            shift
            OUT="${1:-}"
            [ -n "$OUT" ] || {
                echo "worklist: --out needs a file" >&2
                exit 2
            }
            ;;
        *)
            echo "worklist: unknown argument '$1'" >&2
            exit 2
            ;;
    esac
    shift
done

for tool in curl jq; do
    command -v "$tool" >/dev/null || {
        echo "worklist: $tool is required but not on PATH" >&2
        exit 1
    }
done

if [ "$ENVIRONMENT" = production ]; then PORT=28081; else PORT=18081; fi
BASE="${EJ_IMPORTER_URL:-http://localhost:$PORT}/api/admin/data-quality/worklist"
PAGE=500

keys="$(curl -s --max-time 20 "$BASE?issue=_" | jq -r '.detail' | sed -n 's/.*Valid values: //p' | tr -d ' ' | tr ',' '\n')"
[ -n "$keys" ] || {
    echo "worklist: no issue keys from $BASE — is the $ENVIRONMENT forward up (scripts/ej.sh status)?" >&2
    exit 1
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

for issue in $keys; do
    offset=0
    : >"$WORK/$issue.pages"
    while :; do
        page="$(curl -sf --max-time 60 "$BASE?issue=$issue&limit=$PAGE&offset=$offset")" || {
            echo "worklist: $issue at offset $offset failed" >&2
            exit 1
        }
        printf '%s\n' "$page" >>"$WORK/$issue.pages"
        [ "$(jq '.entries | length' <<<"$page")" -lt "$PAGE" ] && break
        offset=$((offset + PAGE))
    done
    jq -s --arg issue "$issue" '{issue: $issue, count: (map(.entries | length) | add), entries: (map(.entries) | add)}' \
        "$WORK/$issue.pages" >"$WORK/$issue.json"
done

result="$(jq -s '.' "$WORK"/*.json)"

if [ "$SUMMARY" = true ]; then
    jq -r '[.[] | .issue as $i | .entries | group_by(.sourceSlug)[]
            | {issue: $i, source: .[0].sourceSlug, count: length, kinds: ([.[].flags[].kind] | unique | join(","))}]
           | sort_by(-.count)[] | [.issue, .source, .count, (if .kinds == "" then "-" else .kinds end)] | @tsv' <<<"$result"
elif [ -n "$OUT" ]; then
    printf '%s\n' "$result" >"$OUT"
    jq -r '.[] | "\(.issue)\t\(.count)"' <<<"$result" >&2
else
    printf '%s\n' "$result"
fi
