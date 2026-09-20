#!/usr/bin/env bash
#
# upstream-node-pins.sh — are the two node pins behind upstream, right now?
#
# k3s and wal-g are pinned as `default =` strings in infra/modules/environment/variables.tf, which no
# manifest declares and no bot sees (#1068, ADR-024).
#
# Usage:
#   scripts/upstream-node-pins.sh                    # one line per pin
#   scripts/upstream-node-pins.sh --json             # for node-pin-reminder.yml
#   scripts/upstream-node-pins.sh --skip-checksums   # faster: no wal-g tarball download
#
# Exit 0 both current, 1 something is behind, 2 the check could not be made — three answers a caller
# must not collapse, because a check that cannot reach upstream and reports "up to date" is the
# failure this exists to close.
#
# **Both versions are read out of the Terraform file, never restated here**, and an extraction that
# matches nothing is an error. **A bump is not routine**: both values feed `user_data`, which is
# force-new, and both also install in place on a running node (docs/ops/K3S_UPGRADE.md, BACKUPS.md
# §8). A person decides; nothing here opens a pull request. **`walg_checksums` moves with
# `walg_version` or the node does not boot** — backups.sh verifies the tarball against the pinned
# SHA-256 — so this prints the two replacement checksums.
#
# Requires: curl, jq, sha256sum or shasum. GITHUB_TOKEN or GH_TOKEN lifts the 60-per-hour anonymous limit.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VARIABLES="$REPO_ROOT/infra/modules/environment/variables.tf"

K3S_CHANNELS='https://update.k3s.io/v1-release/channels'
WALG_RELEASE='https://api.github.com/repos/wal-g/wal-g/releases/latest'
WALG_DOWNLOAD='https://github.com/wal-g/wal-g/releases/download'

# The asset name backups.sh builds, keyed by the dpkg architecture that names the checksum in
# Terraform (`arm64` there, `aarch64` in the release).
WALG_ASSET_amd64='wal-g-pg-24.04-amd64'
WALG_ASSET_arm64='wal-g-pg-24.04-aarch64'

JSON=0
CHECKSUMS=1

die() {
    printf 'upstream-node-pins.sh: %s\n' "$1" >&2
    exit 2
}

usage() {
    awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "${BASH_SOURCE[0]}"
}

while [[ $# -gt 0 ]]; do
    case "$1" in
        --json) JSON=1 ;;
        --skip-checksums) CHECKSUMS=0 ;;
        -h | --help)
            usage
            exit 0
            ;;
        *) die "unknown argument $1" ;;
    esac
    shift
done

for tool in curl jq; do
    command -v "$tool" >/dev/null || die "$tool is required but not on PATH"
done
[[ -f "$VARIABLES" ]] || die "$VARIABLES does not exist"

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

sha256_of() {
    if command -v sha256sum >/dev/null; then
        sha256sum "$1" | awk '{print $1}'
    elif command -v shasum >/dev/null; then
        shasum -a 256 "$1" | awk '{print $1}'
    else
        die 'neither sha256sum nor shasum is on PATH'
    fi
}

# `curl --fail` throughout, so an outage is an error and never an empty answer.
fetch() {
    local url="$1" out="$2"
    local -a auth=()
    local token="${GITHUB_TOKEN:-${GH_TOKEN:-}}"
    if [[ "$url" == https://api.github.com/* && -n "$token" ]]; then
        auth=(-H "Authorization: Bearer ${token}")
    fi
    curl -fsSL --retry 3 "${auth[@]}" "$url" -o "$out" || die "could not fetch $url"
}

# hcl_default <variable> — the quoted `default =` of a scalar variable block. Anchored to the
# assignment: both blocks name the version in prose too.
hcl_default() {
    awk -v want="$1" '
        $0 ~ "^variable \"" want "\" \\{" { inblock = 1; next }
        inblock && /^\}/ { exit }
        inblock && /^[[:space:]]*default[[:space:]]*=/ {
            if (match($0, /"[^"]*"/)) { print substr($0, RSTART + 1, RLENGTH - 2); exit }
        }
    ' "$VARIABLES"
}

# hcl_map_default <variable> <key> — one entry of a map-valued `default = { … }`.
hcl_map_default() {
    awk -v want="$1" -v key="$2" '
        $0 ~ "^variable \"" want "\" \\{" { inblock = 1; next }
        inblock && /^\}/ { exit }
        inblock && /^[[:space:]]*default[[:space:]]*=[[:space:]]*\{/ { inmap = 1; next }
        inmap && $0 ~ "^[[:space:]]*" key "[[:space:]]*=" {
            if (match($0, /"[^"]*"/)) { print substr($0, RSTART + 1, RLENGTH - 2); exit }
        }
    ' "$VARIABLES"
}

require() {
    [[ -n "$2" ]] || die "no $1 found in ${VARIABLES#"$REPO_ROOT/"} — the extraction matched nothing, which is not the same as up to date"
    printf '%s' "$2"
}

minor_of() { sed -E 's/^(v?[0-9]+\.[0-9]+).*/\1/' <<<"$1"; }

K3S_PINNED="$(require 'k3s_version default' "$(hcl_default k3s_version)")"
WALG_PINNED="$(require 'walg_version default' "$(hcl_default walg_version)")"
WALG_SHA_amd64="$(require 'walg_checksums.amd64' "$(hcl_map_default walg_checksums amd64)")"
WALG_SHA_arm64="$(require 'walg_checksums.arm64' "$(hcl_map_default walg_checksums arm64)")"

# ---------------------------------------------------------------------------
# k3s
# ---------------------------------------------------------------------------

# The stable channel, not the newest GitHub release: "newest tag" regularly means a minor nobody is
# being asked to move to.
fetch "$K3S_CHANNELS" "$WORK/channels.json"

K3S_STABLE="$(jq -r '.data[] | select(.id == "stable") | .latest // empty' "$WORK/channels.json")"
[[ -n "$K3S_STABLE" ]] || die "$K3S_CHANNELS carries no stable channel"

K3S_PINNED_MINOR="$(minor_of "$K3S_PINNED")"
K3S_STABLE_MINOR="$(minor_of "$K3S_STABLE")"

# What the pinned line itself offers — the conservative bump, a different piece of work from
# crossing a minor.
K3S_LINE_LATEST="$(jq -r --arg c "$K3S_PINNED_MINOR" '.data[] | select(.id == $c) | .latest // empty' "$WORK/channels.json")"
[[ -n "$K3S_LINE_LATEST" ]] || die "$K3S_CHANNELS carries no ${K3S_PINNED_MINOR} channel — the pinned line may be out of support"

if [[ "$K3S_PINNED" == "$K3S_STABLE" ]]; then
    K3S_STATUS=current K3S_GAP=none
elif [[ "$K3S_PINNED_MINOR" == "$K3S_STABLE_MINOR" ]]; then
    K3S_STATUS=behind K3S_GAP=patch
else
    K3S_STATUS=behind K3S_GAP=minor
fi

# ---------------------------------------------------------------------------
# wal-g
# ---------------------------------------------------------------------------

# `/releases/latest` excludes drafts and prereleases by itself, and wal-g ships those.
fetch "$WALG_RELEASE" "$WORK/walg.json"

WALG_LATEST="$(jq -r '.tag_name // empty' "$WORK/walg.json")"
[[ -n "$WALG_LATEST" ]] || die "$WALG_RELEASE carries no tag_name"

WALG_NEW_amd64='' WALG_NEW_arm64=''
if [[ "$WALG_PINNED" == "$WALG_LATEST" ]]; then
    WALG_STATUS=current
else
    WALG_STATUS=behind
fi

# walg_checksum <asset> — the SHA-256 of the release asset the node would download, computed from the
# tarball and compared with the `.sha256` beside it. Both come from the same release, so this is not
# independent verification; a mismatch is still worth refusing to report.
walg_checksum() {
    local asset="$1" tarball="$WORK/$1.tar.gz" computed published
    fetch "${WALG_DOWNLOAD}/${WALG_LATEST}/${asset}.tar.gz" "$tarball"
    fetch "${WALG_DOWNLOAD}/${WALG_LATEST}/${asset}.tar.gz.sha256" "$WORK/$asset.sha256"

    computed="$(sha256_of "$tarball")"
    published="$(awk '{print $1; exit}' "$WORK/$asset.sha256")"
    [[ "$computed" == "$published" ]] ||
        die "${asset} for ${WALG_LATEST}: computed ${computed} but the release publishes ${published}"

    printf '%s' "$computed"
}

if [[ "$WALG_STATUS" == behind && "$CHECKSUMS" == 1 ]]; then
    WALG_NEW_amd64="$(walg_checksum "$WALG_ASSET_amd64")"
    WALG_NEW_arm64="$(walg_checksum "$WALG_ASSET_arm64")"
fi

# ---------------------------------------------------------------------------
# Report
# ---------------------------------------------------------------------------

if [[ "$JSON" == 1 ]]; then
    jq -n \
        --arg k3sPinned "$K3S_PINNED" --arg k3sStable "$K3S_STABLE" \
        --arg k3sLine "$K3S_LINE_LATEST" --arg k3sPinnedMinor "$K3S_PINNED_MINOR" \
        --arg k3sStableMinor "$K3S_STABLE_MINOR" --arg k3sStatus "$K3S_STATUS" --arg k3sGap "$K3S_GAP" \
        --arg walgPinned "$WALG_PINNED" --arg walgLatest "$WALG_LATEST" --arg walgStatus "$WALG_STATUS" \
        --arg walgOldAmd64 "$WALG_SHA_amd64" --arg walgOldArm64 "$WALG_SHA_arm64" \
        --arg walgNewAmd64 "$WALG_NEW_amd64" --arg walgNewArm64 "$WALG_NEW_arm64" \
        '{
            k3s: {
                name: "k3s", pinned: $k3sPinned, latest: $k3sStable, status: $k3sStatus, gap: $k3sGap,
                pinnedMinor: $k3sPinnedMinor, latestMinor: $k3sStableMinor, lineLatest: $k3sLine,
            },
            walg: {
                name: "wal-g", pinned: $walgPinned, latest: $walgLatest, status: $walgStatus,
                checksums: { amd64: $walgNewAmd64, arm64: $walgNewArm64 },
                pinnedChecksums: { amd64: $walgOldAmd64, arm64: $walgOldArm64 },
            },
        }'
else
    printf '%-8s %-16s %-16s %s\n' pin pinned upstream status
    printf '%-8s %-16s %-16s %s\n' k3s "$K3S_PINNED" "$K3S_STABLE" \
        "$(if [[ "$K3S_STATUS" == current ]]; then echo current; else echo "behind (${K3S_GAP}); the ${K3S_PINNED_MINOR} line offers ${K3S_LINE_LATEST}"; fi)"
    printf '%-8s %-16s %-16s %s\n' wal-g "$WALG_PINNED" "$WALG_LATEST" "$WALG_STATUS"
    if [[ -n "$WALG_NEW_amd64" ]]; then
        printf '%-8s walg_checksums amd64 = %s\n' '' "$WALG_NEW_amd64"
        printf '%-8s walg_checksums arm64 = %s\n' '' "$WALG_NEW_arm64"
    fi
    if [[ "$K3S_STATUS" == behind || "$WALG_STATUS" == behind ]]; then
        printf '\nNeither bump is routine: docs/ops/K3S_UPGRADE.md, and docs/ops/BACKUPS.md section 8.\n'
    fi
fi

[[ "$K3S_STATUS" == current && "$WALG_STATUS" == current ]] || exit 1
