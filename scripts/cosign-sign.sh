#!/usr/bin/env bash
#
# cosign-sign.sh — sign each published artifact by digest, then verify the signature the clusters check.
#
# Usage:
#   scripts/cosign-sign.sh <registry/name@sha256:...>...   # e.g. ghcr.io/enorm-labs/event-junkie/bff@sha256:…
#
# Requires: cosign, an OIDC token (`id-token: write`) and a registry login, as in `release.yml`. Reads
# GITHUB_REPOSITORY and GITHUB_REF for the identity. Reaches Fulcio, Rekor and the registry.
#
# Rekor answered `409 createLogEntryConflict` twice in two days (#3297, #3328): an upload that timed out,
# retried inside cosign, and met its own first entry. cosign then stops before it pushes the signature,
# so the 409 is not a signed image. A new attempt makes a new ephemeral key and a new entry (#3331).
# `cosign verify` reads Rekor too, so it gets the same retry. ATTEMPTS (3) and RETRY_SECONDS (30) tune it.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
    "")
        echo "cosign-sign: usage: $0 <registry/name@sha256:...>..." >&2
        exit 2
        ;;
esac

ATTEMPTS="${ATTEMPTS:-3}"
RETRY_SECONDS="${RETRY_SECONDS:-30}"
: "${GITHUB_REPOSITORY:?cosign-sign: GITHUB_REPOSITORY is not set}"
: "${GITHUB_REF:?cosign-sign: GITHUB_REF is not set}"
IDENTITY="https://github.com/${GITHUB_REPOSITORY}/.github/workflows/release.yml@${GITHUB_REF}"
ISSUER="https://token.actions.githubusercontent.com"

# A reference without a digest would sign whatever the tag points at, or nothing (#1087).
for ref in "$@"; do
    if [[ "$ref" != *@sha256:?* ]]; then
        echo "::error::cosign-sign: not a digest reference: '${ref}'"
        exit 2
    fi
done

# Runs "$@" up to ATTEMPTS times; a failed attempt warns, and only the last one fails the script.
with_retries() {
    local what="$1" attempt
    shift
    for ((attempt = 1; attempt <= ATTEMPTS; attempt++)); do
        if "$@"; then
            return 0
        fi
        echo "::warning::cosign-sign: ${what} failed, attempt ${attempt} of ${ATTEMPTS}"
        if ((attempt < ATTEMPTS)); then
            sleep "$RETRY_SECONDS"
        fi
    done
    echo "::error::cosign-sign: ${what} failed ${ATTEMPTS} times"
    return 1
}

for ref in "$@"; do
    with_retries "signing ${ref}" cosign sign --yes "$ref"
    with_retries "verifying ${ref}" cosign verify --certificate-oidc-issuer "$ISSUER" --certificate-identity "$IDENTITY" "$ref" >/dev/null
    echo "cosign-sign: signed and verified ${ref}"
done
