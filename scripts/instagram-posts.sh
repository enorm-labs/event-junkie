#!/usr/bin/env bash
#
# instagram-posts.sh — read the recent posts of a venue's Instagram business account, as the importer reads them (ADR-048, ADR-049).
#
# Usage:
#   scripts/instagram-posts.sh <handle> [--limit N]   # one line per post: date, type, permalink, caption
#   scripts/instagram-posts.sh <handle> --json        # Business Discovery's answer, unchanged
#
# Calls Meta's Graph API Business Discovery as our account eventjunkie.berlin, with the fields the
# importer reads. The image links are signed and expire within days. The token comes from INSTAGRAM_ACCESS_TOKEN, else from the
# macOS keychain item `instagram-token`. It goes to curl on stdin, so it never shows in `ps`, and it
# is never printed. INSTAGRAM_IG_USER_ID and INSTAGRAM_GRAPH_VERSION override the defaults.
#
# --limit defaults to 25, Meta's page size. Error 110 means a wrong handle or a personal account,
# which the API does not tell apart. Error 10 is a missing permission (`instagram_manage_insights`),
# 190 an expired token. Exit code: 0 on posts, 1 on a Graph error, 2 on bad arguments or no token.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
        exit 0
        ;;
    "")
        echo "instagram-posts.sh: a handle is required; see --help" >&2
        exit 2
        ;;
esac

handle="${1#@}"
shift
limit=25
raw=false
while [[ $# -gt 0 ]]; do
    case "$1" in
        --limit)
            limit="${2:?--limit needs a number}"
            shift 2
            ;;
        --json)
            raw=true
            shift
            ;;
        *)
            echo "instagram-posts.sh: unknown argument '$1'; see --help" >&2
            exit 2
            ;;
    esac
done
if ! [[ "$handle" =~ ^[A-Za-z0-9._]+$ && "$limit" =~ ^[0-9]+$ ]]; then
    echo "instagram-posts.sh: the handle takes letters, digits, '.' and '_', and --limit a number" >&2
    exit 2
fi

token="${INSTAGRAM_ACCESS_TOKEN:-}"
if [[ -z "$token" ]] && command -v security >/dev/null; then
    token="$(security find-generic-password -s instagram-token -w 2>/dev/null || true)"
fi
if [[ -z "$token" ]]; then
    echo "instagram-posts.sh: no token; set INSTAGRAM_ACCESS_TOKEN or the keychain item 'instagram-token'" >&2
    exit 2
fi

ig_user_id="${INSTAGRAM_IG_USER_ID:-17841471622815307}"
version="${INSTAGRAM_GRAPH_VERSION:-v26.0}"
fields="business_discovery.username(${handle}){username,name,media_count,media.limit(${limit}){id,caption,permalink,timestamp,media_type,media_url,thumbnail_url,children{media_type,media_url,thumbnail_url}}}"

response="$(printf 'header = "Authorization: Bearer %s"\n' "$token" |
    curl -sS -K - -G "https://graph.facebook.com/${version}/${ig_user_id}" --data-urlencode "fields=${fields}")"

if jq -e '.error' >/dev/null <<<"$response"; then
    jq -r '.error | "Graph error \(.code)/\(.error_subcode // "-"): \(.message)"' <<<"$response" >&2
    exit 1
fi
if [[ "$raw" == true ]]; then
    jq . <<<"$response"
    exit 0
fi
jq -r '.business_discovery |
    "\(.name) (@\(.username)), \(.media_count) posts",
    (.media.data[] | "\(.timestamp[0:10])  \(.media_type[0:8])  \(.permalink)\n    \((.caption // "") | gsub("\n+"; " / "))")' <<<"$response"
