#!/usr/bin/env bash
#
# outline-text.sh — set a string in a font and print it as an SVG path.
#
# Usage:
#   scripts/outline-text.sh --font <file> --text <string> --size <n> --baseline <n> [...]
#   scripts/outline-text.sh --help
#
# The brand artwork in docs/branding/ carries outlined glyphs rather than <text>, and this produces
# them; scripts/outline_text.py has why that is not optional. fontTools is PINNED and installed into
# a throwaway venv, because this emits coordinates and a version that rounds or converts curves
# differently would silently redraw artwork already committed and reviewed — the failure mode
# format-markdown.sh's oxfmt pin guards. Bump the pin deliberately and re-render every SVG.
#
# The network is reached only on first run, to build that venv in .venv-fonts/ (gitignored; delete
# it to force a rebuild). brotli comes along because reading a .woff2 needs it, and the self-hosted
# faces ship as woff2 inside node_modules.
set -euo pipefail

readonly FONTTOOLS_VERSION='4.63.0'
readonly BROTLI_VERSION='1.2.0'

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
venv="${repo_root}/.venv-fonts"
stamp="${venv}/.pinned-${FONTTOOLS_VERSION}-${BROTLI_VERSION}"

# The stamp encodes the pins, so bumping either rebuilds rather than reusing a stale environment.
if [[ ! -f "${stamp}" ]]; then
    echo "outline-text: building ${venv} (fontTools ${FONTTOOLS_VERSION})…" >&2
    rm -rf "${venv}"
    python3 -m venv "${venv}"
    "${venv}/bin/pip" install --quiet --disable-pip-version-check \
        "fonttools==${FONTTOOLS_VERSION}" "brotli==${BROTLI_VERSION}"
    touch "${stamp}"
fi

exec "${venv}/bin/python" "${repo_root}/scripts/outline_text.py" "$@"
