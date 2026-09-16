#!/usr/bin/env bash
#
# scope-parity.sh — one list of product scopes, nine places, and nothing else joining them.
#
# Usage:
#   scripts/scope-parity.sh          # exits 1 listing whatever is out of step
#
# Reaches no network and writes nothing.
#
# `feat` is reserved for a change a visitor to the site can see, and "can see" is a list of scopes.
# Three places act on it: `label-pr.yml` goes red on a `feat` outside it, `version.sh deserved`
# counts such a `feat` as a patch, and `release-highlights.sh` leaves it out of the summary. Six more
# tell a person or an agent what the list is. The labeller runs with no checkout, so it cannot read
# a shared file, and the copies are the price of that.
#
# The labeller's copy is the canonical one, because it is the one that says no. Every other copy
# must equal it, in the same order, so a scope added to the labeller alone lets a title through that
# `deserved` then reads as a patch, and a scope added to `deserved` alone earns a minor the labeller
# refused. Neither of those errors — both are two green checks disagreeing.

set -euo pipefail

case "${1:-}" in
    -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

problems=0

note() {
    printf '  %s\n' "$1" >&2
    problems=1
}

# `const PRODUCT_SCOPES = new Set(['frontend', 'events', …]);` → one scope per line, in order.
canonical="$(sed -n "s/.*const PRODUCT_SCOPES = new Set(\[\(.*\)\]).*/\1/p" .github/workflows/label-pr.yml |
    tr -d "' " | tr ',' '\n')"
[ -n "$canonical" ] || { note "label-pr.yml: no 'const PRODUCT_SCOPES = new Set([…])' line to read"; }

# The two scripts hold the list as one space-padded string, so a substring match on " scope " works.
shell_list() { sed -n "s/^.*$2=\" \(.*\) \".*$/\1/p" "$1" | tr ' ' '\n'; }

compare() {
    local file="$1" found="$2"
    if [ -z "$found" ]; then
        note "$file: no scope list found"
    elif ! diff -q <(printf '%s\n' "$canonical") <(printf '%s\n' "$found") >/dev/null 2>&1; then
        note "$file: the scope list differs from label-pr.yml's"
        diff <(printf '%s\n' "$canonical") <(printf '%s\n' "$found") | sed 's/^/      /' >&2 || true
    fi
}

compare scripts/version.sh "$(shell_list scripts/version.sh 'local product_scopes')"
compare scripts/release-highlights.sh "$(shell_list scripts/release-highlights.sh 'PRODUCT_SCOPES')"

# Prose quotes the list as `frontend`, `events`, … — one run of code spans, wrapped wherever the line
# broke. Whitespace is collapsed before the match, so a wrap is not a difference, and the match is
# the whole run in order, so a scope missing, added or moved in one copy is.
# shellcheck disable=SC2016 # the backticks are Markdown code spans, not a substitution
rendered="$(printf '%s\n' "$canonical" | sed 's/.*/`&`/' | paste -sd, - | sed 's/,/, /g')"
for file in \
    .github/prompts/release-highlights.prompt.md \
    .github/prompts/commit-message.prompt.md \
    .github/prompts/squash-commit-message.prompt.md \
    .github/instructions/ci-cd.instructions.md \
    docs/ops/RELEASING.md \
    CONTRIBUTING.md; do
    [ -f "$file" ] || { note "$file: missing"; continue; }
    tr -s '[:space:]' ' ' <"$file" | grep -qF "$rendered" ||
        note "$file: does not quote the list as label-pr.yml has it: $rendered"
done

if [ "$problems" -ne 0 ]; then
    echo >&2
    echo "The product scopes are out of step. label-pr.yml is the copy to match." >&2
    exit 1
fi

printf 'Product scopes agree: %s of them, in nine places.\n' "$(printf '%s\n' "$canonical" | grep -c .)"
