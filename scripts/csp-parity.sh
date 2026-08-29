#!/usr/bin/env bash
#
# csp-parity.sh — the Content-Security-Policy exists twice, and the two copies must agree.
#
# Usage:
#   scripts/csp-parity.sh [chart-dir]      # default: deploy/charts/event-junkie
#
# Requires: yq and python3. Reaches no network, writes nothing, and needs no cluster.
#
# The header a visitor gets is set by a Traefik Middleware, so the copy that reaches production is
# `values.yaml`. The second copy is `events-frontend/scripts/csp.ts`, which applies the same policy
# to `npm run preview` — the server Playwright runs against on CI. Without that second copy the e2e
# suite passes whatever the policy says, and the first evidence of a wrong one is a blank page on
# staging. With it, the copies can drift instead, which is what this script is for (#846).
#
# It asserts three things:
#
# 1. **The two directive lists are identical.** `img-src` is in neither, deliberately — the chart
#    derives it from `images.serving.enabled`, because with serving off the API hands out the
#    venue's own URL and `'self'` would blank every image on the site.
#
# 2. **The `script-src` hash matches the inline script in `events-frontend/index.html`.** This is the
#    one that will actually fire one day. A nonce cannot work through a static middleware header, so
#    the theme script is allowed by hash — and editing that script changes the hash. **The failure
#    is silent in the worst way**: the script is blocked, the theme falls back to light for one
#    frame, and nothing else about the site is wrong. Vite copies the script into `dist/index.html`
#    byte for byte, so the source file is the right thing to hash.
#
# 3. **No hash is allowed that no script needs.** A stale entry left behind after an edit permits
#    bytes that are no longer served, which is the whole value of a hash given away for nothing.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART="${1:-$REPO_ROOT/deploy/charts/event-junkie}"
VALUES="$CHART/values.yaml"
CSP_TS="$REPO_ROOT/events-frontend/scripts/csp.ts"
INDEX_HTML="$REPO_ROOT/events-frontend/index.html"

command -v yq >/dev/null || {
  printf 'csp-parity.sh: yq is required but not on PATH\n' >&2
  exit 1
}

for f in "$VALUES" "$CSP_TS" "$INDEX_HTML"; do
  [[ -f "$f" ]] || {
    printf 'csp-parity.sh: no such file: %s\n' "$f" >&2
    exit 1
  }
done

failures=0

fail() {
  printf 'csp-parity.sh: %s\n' "$1" >&2
  failures=$((failures + 1))
}

# --- 1. The two directive lists -----------------------------------------------------------------

chart_directives="$(yq -N -r \
  '.ingress.securityHeaders.contentSecurityPolicy.directives[]' "$VALUES")"

# The TypeScript is read rather than executed: running it would need a Node with type stripping and
# a working install, which is a great deal of machinery to compare ten strings. The array is a plain
# list of double-quoted literals, and the extractor below refuses anything else rather than guessing.
frontend_directives="$(python3 - "$CSP_TS" <<'PY'
import re
import sys

source = open(sys.argv[1], encoding="utf-8").read()
match = re.search(r"export const CSP_DIRECTIVES = \[(.*?)\n\]", source, re.S)
if not match:
    sys.exit("could not find `export const CSP_DIRECTIVES = [ ... ]`")
body = re.sub(r"//[^\n]*", "", match.group(1))
directives = re.findall(r'"([^"]*)"', body)
if not directives:
    sys.exit("CSP_DIRECTIVES parsed as empty")
print("\n".join(directives))
PY
)"

if [[ "$chart_directives" != "$frontend_directives" ]]; then
  fail "the two policies differ. values.yaml and events-frontend/scripts/csp.ts must carry the same list:"
  diff <(printf '%s\n' "$chart_directives") <(printf '%s\n' "$frontend_directives") \
    --label "$VALUES" --label "$CSP_TS" -u >&2 || true
fi

# `img-src` belongs to neither copy. Asserted rather than assumed, because adding it to the list is
# the obvious-looking fix for the first person who finds images blocked, and it would pin the header
# to one deployment's answer.
if printf '%s\n' "$chart_directives" | grep -q '^img-src'; then
  fail "values.yaml lists an img-src directive. The template derives it from images.serving.enabled — see the values comment"
fi

# --- 2 and 3. The script hashes -----------------------------------------------------------------

# Every inline `<script>` in the page, hashed the way CSP asks: SHA-256 over the element's exact
# text content, base64, prefixed. `src=` scripts are excluded — they are covered by `'self'`.
expected_hashes="$(python3 - "$INDEX_HTML" <<'PY'
import base64
import hashlib
import re
import sys

html = open(sys.argv[1], encoding="utf-8").read()
inline = re.findall(r"<script(?![^>]*\ssrc=)[^>]*>(.*?)</script>", html, re.S)
for body in inline:
    digest = hashlib.sha256(body.encode("utf-8")).digest()
    print("sha256-" + base64.b64encode(digest).decode("ascii"))
PY
)"

declared_hashes="$(printf '%s\n' "$chart_directives" | grep -o "sha256-[A-Za-z0-9+/]*=*" | sort -u || true)"
expected_sorted="$(printf '%s\n' "$expected_hashes" | sort -u)"

while IFS= read -r hash; do
  [[ -n "$hash" ]] || continue
  if ! printf '%s\n' "$declared_hashes" | grep -qxF "$hash"; then
    fail "events-frontend/index.html has an inline script the policy does not allow: $hash — add it to script-src in both copies"
  fi
done <<<"$expected_sorted"

while IFS= read -r hash; do
  [[ -n "$hash" ]] || continue
  if ! printf '%s\n' "$expected_sorted" | grep -qxF "$hash"; then
    fail "script-src allows $hash, which no inline script in events-frontend/index.html produces — a stale hash permits bytes nobody serves"
  fi
done <<<"$declared_hashes"

# --- The verdict ---------------------------------------------------------------------------------

if [[ "$failures" -gt 0 ]]; then
  printf '\ncsp-parity.sh: %d problem(s). The chart, the preview server and index.html must agree.\n' "$failures" >&2
  exit 1
fi

directive_count="$(printf '%s\n' "$chart_directives" | grep -c . || true)"
hash_count="$(printf '%s\n' "$expected_sorted" | grep -c . || true)"
printf 'CSP agrees: %s directives in both copies, %s inline script hash(es) matching index.html.\n' \
  "$directive_count" "$hash_count"
