#!/usr/bin/env bash
#
# instagram-page-token.sh — make the Page token the Instagram drafts read with (ADR-048), and store it in the keychain.
#
# Usage:
#   scripts/instagram-page-token.sh   # prompts for the app secret and a short-lived user token
#
# The exchange Meta documents: a short-lived user token of the app's admin becomes a long-lived one
# (about 60 days), and the Event Junkie Page's token read with it has no expiry. Meta's data access for
# it still ends 90 days after this run, so run it again before then (credential-expiry-reminder.yml).
# A system user token is refused by Business Discovery without App Review, so this is the token the clusters use.
# Take the user token from the Graph API Explorer: app "Event Junkie Importer", User Token, the
# permissions instagram_basic, instagram_manage_insights, pages_read_engagement and pages_show_list, and no others.
# The app secret is on App settings → Basic, 32 hex characters.
#
# Both values are read without echo and go to curl on stdin, so neither shows in `ps` or a history.
# The Page token goes into the keychain item `instagram-token`, replacing any older one, and is never
# printed. A Facebook password change or the loss of admin rights on the Page ends it: run this again.
# Reaches graph.facebook.com only. Exit code: 0 when the token is stored and reads Prisma's account,
# 1 when Meta refuses a step, 2 on a malformed secret.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
        exit 0
        ;;
esac

readonly APP_ID=1412309140478472
readonly PAGE_ID=1458453760679949
readonly GRAPH=https://graph.facebook.com/v26.0

read -rsp "App secret: " secret && echo
secret="${secret//[[:space:]]/}"
if ! [[ "$secret" =~ ^[0-9a-f]{32}$ ]]; then
    echo "instagram-page-token.sh: an app secret is 32 hex characters; this one has ${#secret}" >&2
    exit 2
fi
read -rsp "Short-lived user token: " short && echo
short="${short//[[:space:]]/}"

exchange="$(printf 'data = "grant_type=fb_exchange_token"\ndata = "client_id=%s"\ndata = "client_secret=%s"\ndata = "fb_exchange_token=%s"\n' \
    "$APP_ID" "$secret" "$short" | curl -sS -G -K - "$GRAPH/oauth/access_token")"
unset secret short
long="$(jq -r '.access_token // empty' <<<"$exchange")"
if [[ -z "$long" ]]; then
    echo "exchange refused: $(jq -r '.error.message // "no answer"' <<<"$exchange")" >&2
    exit 1
fi

# By the Page's id, not from /me/accounts: that list omits a portfolio-owned Page unless the token holds business_management.
answer="$(printf 'header = "Authorization: Bearer %s"\n' "$long" | curl -sS -K - "$GRAPH/$PAGE_ID?fields=access_token")"
unset long
page="$(jq -r '.access_token // empty' <<<"$answer")"
if [[ -z "$page" ]]; then
    echo "no token for Page $PAGE_ID: $(jq -r '.error.message // "Meta returned none; tick the Page in the login dialog"' <<<"$answer")" >&2
    exit 1
fi

security delete-generic-password -s instagram-token >/dev/null 2>&1 || true
security add-generic-password -s instagram-token -a eventjunkie -w "$page"
unset page
echo "stored in the keychain item instagram-token"
"$(dirname "${BASH_SOURCE[0]}")/instagram-posts.sh" prisma.berlin --limit 1
