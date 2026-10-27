#!/usr/bin/env bash
#
# vapid-keys.sh — make a VAPID key pair for Web Push (ADR-055) and print the command that stores it.
#
# The BFF signs every push with the private key, and a browser subscribes with the public one. A subscription is
# bound to the public key, so a new pair ends every subscription on that cluster. Make one pair per cluster, once.
#
# Usage: scripts/vapid-keys.sh <staging|production>
#
# Needs `openssl`. Prints the `kubectl create secret` command with both keys, base64url without padding, for the
# operator to run. Writes no file and talks to no cluster. Exit code: 0, or 2 on bad arguments.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
    staging | production) ;;
    *)
        echo "vapid-keys: needs staging or production; see --help" >&2
        exit 2
        ;;
esac

base64url() { openssl base64 -A | tr '+/' '-_' | tr -d '='; }

pem=$(openssl ecparam -name prime256v1 -genkey -noout)
der=$(mktemp)
trap 'rm -f "$der"' EXIT
printf '%s\n' "$pem" | openssl ec -outform DER -out "$der" 2>/dev/null
# A P-256 key in SEC 1 DER: the 32-byte scalar starts at byte 7, and the 65-byte public point ends the file.
private=$(head -c 39 "$der" | tail -c 32 | base64url)
public=$(tail -c 65 "$der" | base64url)

cat <<OUT
kubectl --context event-junkie-$1 -n event-junkie create secret generic event-junkie-push \\
  --from-literal=APP_PUSH_VAPID_PRIVATE_KEY='$private' \\
  --from-literal=APP_PUSH_VAPID_PUBLIC_KEY='$public'
OUT
