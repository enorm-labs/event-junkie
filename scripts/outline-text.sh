#!/usr/bin/env bash
#
# outline-text.sh — set a string in a font and print it as an SVG path.
#
# Usage:
#   scripts/outline-text.sh --font <file> --text <string> --size <n> --baseline <n> [...]
#   scripts/outline-text.sh --help
#
# The brand artwork in docs/branding/ carries outlined glyphs rather than <text>,
# and this is what produces them. See scripts/outline_text.py for why that is not
# optional — the short version is that a renderer substitutes a missing font
# silently, so a <text> logo can be the wrong typeface and look perfectly fine.
#
# Two things about this script are deliberate.
#
# 1. fontTools is PINNED, and installed into a throwaway venv rather than onto
#    the machine. This emits coordinates: a version that rounds differently, or
#    converts curves differently, would silently redraw artwork that is already
#    committed and reviewed. Same reasoning as format-markdown.sh's oxfmt pin,
#    and the same failure mode — output that changes depending on whose laptop
#    ran it. Bump the pin deliberately, and re-render every SVG when you do.
#
# 2. It reaches the network only on first run, to build that venv. Afterwards it
#    is offline. The venv lives in .venv-fonts/ and is gitignored; delete it to
#    force a clean rebuild.
#
# Reading a .woff2 needs brotli, which is why that is installed too — Geist and
# the other self-hosted faces ship as woff2 inside node_modules, so this is the
# common case rather than the exotic one.
set -euo pipefail

readonly FONTTOOLS_VERSION='4.63.0'
readonly BROTLI_VERSION='1.2.0'

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
venv="${repo_root}/.venv-fonts"
stamp="${venv}/.pinned-${FONTTOOLS_VERSION}-${BROTLI_VERSION}"

# The stamp encodes the pins, so bumping either rebuilds rather than silently
# reusing an environment that no longer matches this file.
if [[ ! -f "${stamp}" ]]; then
    echo "outline-text: building ${venv} (fontTools ${FONTTOOLS_VERSION})…" >&2
    rm -rf "${venv}"
    python3 -m venv "${venv}"
    "${venv}/bin/pip" install --quiet --disable-pip-version-check \
        "fonttools==${FONTTOOLS_VERSION}" "brotli==${BROTLI_VERSION}"
    touch "${stamp}"
fi

exec "${venv}/bin/python" "${repo_root}/scripts/outline_text.py" "$@"
