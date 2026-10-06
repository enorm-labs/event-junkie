#!/usr/bin/env bash
#
# build-stickers.sh — regenerate the print-ready sticker files in docs/branding/stickers/.
#
# Usage:
#   scripts/build-stickers.sh                 # rewrite docs/branding/stickers/
#   scripts/build-stickers.sh --out <dir>     # write somewhere else, e.g. to compare against the committed files
#   scripts/build-stickers.sh --help
#
# Needs rsvg-convert (`brew install librsvg`, or `apt install librsvg2-bin`) and the frontend's node_modules, which hold
# the Geist font (`npm ci` in events-frontend/). The Python packages are PINNED into a throwaway venv in
# .venv-stickers/ (gitignored), for the reason outline-text.sh pins fontTools: this emits coordinates and colour values,
# and a version that writes them differently would silently change committed print files. fontTools and brotli take
# their pins from outline-text.sh, so the two scripts cannot outline the same string differently.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
eval "$(grep -E '^readonly (FONTTOOLS|BROTLI)_VERSION=' "${repo_root}/scripts/outline-text.sh")"
readonly SEGNO_VERSION='1.6.6'
readonly PIKEPDF_VERSION='10.16.0'
readonly ZXING_VERSION='3.1.1'
readonly PILLOW_VERSION='12.3.0'

command -v rsvg-convert >/dev/null || {
    echo "build-stickers: no rsvg-convert — brew install librsvg" >&2
    exit 1
}

venv="${repo_root}/.venv-stickers"
stamp="${venv}/.pinned-${FONTTOOLS_VERSION}-${BROTLI_VERSION}-${SEGNO_VERSION}-${PIKEPDF_VERSION}-${ZXING_VERSION}-${PILLOW_VERSION}"

if [[ ! -f "${stamp}" ]]; then
    echo "build-stickers: building ${venv}…" >&2
    rm -rf "${venv}"
    python3 -m venv "${venv}"
    "${venv}/bin/pip" install --quiet --disable-pip-version-check \
        "fonttools==${FONTTOOLS_VERSION}" "brotli==${BROTLI_VERSION}" "segno==${SEGNO_VERSION}" \
        "pikepdf==${PIKEPDF_VERSION}" "zxing-cpp==${ZXING_VERSION}" "pillow==${PILLOW_VERSION}"
    touch "${stamp}"
fi

exec "${venv}/bin/python" "${repo_root}/scripts/build_stickers.py" "$@"
