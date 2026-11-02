#!/usr/bin/env bash
#
# secrets-parity.sh — the number of cluster secrets SECRETS.md states, against the rows it lists,
# and the names of its summary table against the names of its exposure table.
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
#
# The exposure table decides per secret whether its ciphertext may go into git. Two secrets added
# after a sweep that gave every secret a row had none for a week (#4393).

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
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

WORDS=(zero one two three four five six seven eight nine ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty)
N="($(
    IFS='|'
    printf '%s' "${WORDS[*]}"
))"

# The first table whose header starts with `| Secret` is the summary, the second the exposure
# table. The count comes from the summary only.
# table_names N prints the first-column names of the Nth such table, sorted.
table_names() {
    # shellcheck disable=SC2016  # The backticks are Markdown punctuation, matched literally.
    awk -v want="$1" '/^\| Secret / { n++; p = (n == want) } p && /^$/ { p = 0 } p' "$SECRETS" |
        grep -oE '^\| `[a-z0-9-]+`' | tr -d '|` ' | sort
}
summary_names="$(table_names 1)"
exposure_names="$(table_names 2)"
rows="$(printf '%s\n' "$summary_names" | grep -c . || true)"
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
expect "$SECRETS" "creates the other $N from the" "$hand_made"
expect "$SECRETS" "^## The $N objects, and where each comes from" "$count"
expect "$SECRETS" "\\*\\*All $N belong in that table\\.\\*\\*" "$count"
expect "$SECRETS" "The table lists $N objects, not $N values" "$count"
expect "$SECRETS" "the least dangerous of the $N on" "$count"
expect "$MODEL" "^\\| The $N cluster secrets" "$count"

if [ -z "$exposure_names" ]; then
    note "$SECRETS has no second table starting \`| Secret\` — the exposure table moved; update this check with the document"
else
    while IFS= read -r name; do
        [ -n "$name" ] && note "$SECRETS: \`$name\` is in the summary table and has no row in the exposure table"
    done < <(comm -23 <(printf '%s\n' "$summary_names") <(printf '%s\n' "$exposure_names"))
    while IFS= read -r name; do
        [ -n "$name" ] && note "$SECRETS: \`$name\` is in the exposure table and not in the summary table"
    done < <(comm -13 <(printf '%s\n' "$summary_names") <(printf '%s\n' "$exposure_names"))
fi

if [ "$problems" -ne 0 ]; then
    echo >&2
    echo "The secrets in $SECRETS are out of step with its tables." >&2
    exit 1
fi

printf 'Secrets agree: %s rows in %s, stated as "%s" everywhere, each with an exposure row.\n' "$rows" "$SECRETS" "$count"
