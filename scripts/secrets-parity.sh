#!/usr/bin/env bash
#
# secrets-parity.sh — the number of cluster secrets SECRETS.md states, against the rows it lists.
#
# Usage:
#   scripts/secrets-parity.sh        # exits 1 listing whatever is out of step
#
# Reaches no network and writes nothing.
#
# The summary table in docs/ops/SECRETS.md is what a rebuild follows, and the count beside it is
# the table's checksum. A row added without moving the count is the defect this exists for: the
# ninth secret landed and the document said "eight" for three weeks, twice re-asserted by hand
# (#1671). The count is written as a word in six places, one of them in THREAT_MODEL.md.

set -euo pipefail

case "${1:-}" in
    -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

SECRETS="docs/ops/SECRETS.md"
MODEL="docs/security/THREAT_MODEL.md"

for f in "$SECRETS" "$MODEL"; do
    [ -f "$f" ] || {
        echo "secrets-parity: $f is missing; the check cannot run" >&2
        exit 1
    }
done

problems=0

note() {
    printf '  %s\n' "$1" >&2
    problems=1
}

WORDS=(zero one two three four five six seven eight nine ten eleven twelve)
N="($(IFS='|'; printf '%s' "${WORDS[*]}"))"

# The first table whose header starts with `| Secret` is the summary; the exposure table further
# down repeats a subset of the names and is not counted.
# shellcheck disable=SC2016  # The backticks are Markdown punctuation, matched literally.
rows="$(awk '/^\| Secret / { p = 1 } p && /^$/ { exit } p' "$SECRETS" | grep -cE '^\| `[a-z-]+`' || true)"
if [ "$rows" -lt 1 ] || [ "$rows" -ge "${#WORDS[@]}" ]; then
    echo "secrets-parity: $SECRETS lists $rows secrets, which this check cannot spell" >&2
    exit 1
fi
count="${WORDS[$rows]}"
hand_made="${WORDS[$((rows - 1))]}"

# Each phrase is matched with the number as a wildcard, then the word found is compared, case
# aside. A missing phrase is a finding too: the sentence was rewritten and the check no longer
# reads it.
expect() {
    local file="$1" pattern="$2" want="$3"
    local found
    found="$(grep -oE "$pattern" "$file" | head -1 || true)"
    if [ -z "$found" ]; then
        note "$file no longer carries a sentence matching /$pattern/ — update this check with the document"
        return
    fi
    local word
    word="$(printf '%s' "$found" | tr '[:upper:]' '[:lower:]' | grep -oE "\\b$N\\b" | head -1)"
    if [ "$word" != "$want" ]; then
        note "$file says \"$found\" — the table lists $rows, so that should read \"$want\""
    fi
}

expect "$SECRETS" "\\*\\*[A-Z][a-z]+ objects\\.\\*\\*" "$count"
expect "$SECRETS" "The other $N are typed by a human" "$hand_made"
expect "$SECRETS" "^## The $N objects, and where each comes from" "$count"
expect "$SECRETS" "\\*\\*All $N belong in that table\\.\\*\\*" "$count"
expect "$SECRETS" "The table lists $N objects, not $N values" "$count"
expect "$SECRETS" "the least dangerous of the $N on" "$count"
expect "$MODEL" "^\\| The $N cluster secrets" "$count"

if [ "$problems" -ne 0 ]; then
    echo >&2
    echo "The secret count is out of step with the table in $SECRETS." >&2
    exit 1
fi

printf 'Secret count agrees: %s rows in %s, stated as "%s" in every place that states it.\n' "$rows" "$SECRETS" "$count"
