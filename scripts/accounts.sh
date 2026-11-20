#!/usr/bin/env bash
#
# accounts.sh — the visitor accounts of one environment, as an aligned table, oldest first.
#
# Usage:
#   scripts/accounts.sh staging
#   scripts/accounts.sh production
#
# One row per account: when it was created and last signed in (UTC dates), its mail language, whether it may report
# problems, its follows, its open problem reports and its address. The address is what
# PUT /api/admin/accounts/can-report takes (http/importer/reports.http).
#
# Needs the forward scripts/ej.sh up <environment> opens (importer on 18081 for staging, 28081 for production);
# EJ_IMPORTER_URL overrides it. Reads only, and writes no file: the table holds addresses, so keep it out of reports.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
    staging) PORT=18081 ;;
    production) PORT=28081 ;;
    "")
        echo "accounts: name the environment: staging or production (--help)" >&2
        exit 2
        ;;
    *)
        echo "accounts: unknown argument '$1'" >&2
        exit 2
        ;;
esac
[ $# -eq 1 ] || {
    echo "accounts: one argument only, the environment" >&2
    exit 2
}

for tool in curl jq column; do
    command -v "$tool" >/dev/null || {
        echo "accounts: $tool is required but not on PATH" >&2
        exit 1
    }
done

URL="${EJ_IMPORTER_URL:-http://localhost:$PORT}/api/admin/accounts"

body="$(curl -sf --max-time 20 "$URL")" || {
    echo "accounts: no answer from $URL — is the $1 forward up (scripts/ej.sh status)?" >&2
    exit 1
}

jq -r '
    (["CREATED", "LAST SIGN-IN", "LOCALE", "CAN REPORT", "FOLLOWS", "REPORTS", "EMAIL"] | @tsv),
    (.[] | [.createdAt[0:10], .lastSignInAt[0:10], .locale, (if .canReport then "yes" else "no" end),
            .follows, .openReports, .email] | @tsv)
' <<<"$body" | column -t -s $'\t'
