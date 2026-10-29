#!/usr/bin/env bash
#
# tem-quota.sh — how many mails the Scaleway project sent this calendar month, against the monthly quota (ADR-054, #4016).
#
# Usage:
#   scripts/tem-quota.sh          # one line: "1234 mails this month: 12 % of the 10000 quota, 411 % of the 300 the plan includes"
#   scripts/tem-quota.sh --json   # Scaleway's two answers, unchanged
#
# Reads Transactional Email's `project-consumption` (`monthly_emails_count`) and `offer-subscriptions`
# (`included_monthly_emails`) in fr-par. `bff.mail.sent` cannot answer this: it counts per pod and per
# cluster, and a restart clears it. The two limits differ: the plan's included mails are free and the
# rest is billed, while the quota stops sending. The API does not return the quota, so it is ADR-054's
# 10,000, or TEM_QUOTA once Scaleway raises it.
#
# The key is the read-only one, never the BFF's SMTP key: from SCW_TEM_READ_KEY, else the keychain item
# `event-junkie-tem-read-key`. The project ID from SCW_PROJECT_ID, else `event-junkie-scaleway-project-id`.
# The key goes to curl on stdin, so it never shows in `ps`, and it is never printed.
#
# Exit code: 0 below 80 % of the quota, 3 at 80 % and more, 1 on a failed call, 2 on bad arguments or no key.
set -euo pipefail

raw=false
case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
        exit 0
        ;;
    --json) raw=true ;;
    "") ;;
    *)
        echo "tem-quota.sh: unknown argument '$1'; see --help" >&2
        exit 2
        ;;
esac

keychain() { command -v security >/dev/null && security find-generic-password -s "$1" -w 2>/dev/null || true; }

key="${SCW_TEM_READ_KEY:-$(keychain event-junkie-tem-read-key)}"
project="${SCW_PROJECT_ID:-$(keychain event-junkie-scaleway-project-id)}"
if [[ -z "$key" || -z "$project" ]]; then
    echo "tem-quota.sh: no key or project ID; set SCW_TEM_READ_KEY and SCW_PROJECT_ID, or the keychain items" \
        "'event-junkie-tem-read-key' and 'event-junkie-scaleway-project-id'" >&2
    exit 2
fi

api="https://api.scaleway.com/transactional-email/v1alpha1/regions/fr-par"

# One call. On an HTTP error, Scaleway's own message goes to stderr and the script exits 1.
get() {
    local body
    if ! body="$(printf 'header = "X-Auth-Token: %s"\n' "$key" |
        curl -sS --fail-with-body -K - -G "$api/$1" --data-urlencode "project_id=$project")"; then
        echo "tem-quota.sh: $1 failed: $(jq -r '.message // .' <<<"$body" 2>/dev/null || echo "$body")" >&2
        exit 1
    fi
    printf '%s' "$body"
}

consumption="$(get project-consumption)"
offers="$(get offer-subscriptions)"

if [[ "$raw" == true ]]; then
    jq -n --argjson c "$consumption" --argjson o "$offers" '{"project-consumption": $c, "offer-subscriptions": $o}'
    exit 0
fi

sent="$(jq -r '.monthly_emails_count // empty' <<<"$consumption")"
if ! [[ "$sent" =~ ^[0-9]+$ ]]; then
    echo "tem-quota.sh: project-consumption carries no monthly_emails_count" >&2
    exit 1
fi
included="$(jq -r '[.offer_subscriptions[]?.included_monthly_emails // 0] | max // 0' <<<"$offers")"
quota="${TEM_QUOTA:-10000}"
if ! [[ "$quota" =~ ^[1-9][0-9]*$ ]]; then
    echo "tem-quota.sh: TEM_QUOTA must be a positive number" >&2
    exit 2
fi

percent=$((sent * 100 / quota))
line="$sent mails this month: $percent % of the $quota quota"
if [[ "$included" =~ ^[1-9][0-9]*$ ]]; then
    line+=", $((sent * 100 / included)) % of the $included the plan includes"
fi
echo "$line"
((percent < 80)) || exit 3
