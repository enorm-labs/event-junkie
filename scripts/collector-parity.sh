#!/usr/bin/env bash
#
# collector-parity.sh — one log field name, four places, and nothing else joining them.
#
# Usage:
#   scripts/collector-parity.sh      # exits 1 listing whatever is out of step
#
# Reaches no network and writes nothing.
#
# A structured field is named in the importer's constants, the BFF's constants, the collector's OTTL
# allowlist and the operator's table in PLATFORM_SETUP.md §7. Two constant objects rather than one is
# deliberate: a shared package in events-core becomes a Spring Modulith module of its own (#945). So
# this check is the substitute for sharing them.
#
# **Every failure here is silent.** A name that never reaches the allowlist is no error and no
# column. The query returns an empty result, which reads as "no such data" rather than "no such
# column", and the operator concludes nothing happened.
#
# The allowlist lifts three names this repository does not write. They need no exemption list,
# because ours are read flat and under the same name and the formatter's are read from a nested path:
#
#     set(log.attributes["url"],       log.cache["url"])            # ours
#     set(log.attributes["errorType"], log.cache["error"]["type"])  # Spring's ECS output
#
# The last two checks are about the prose, and they are the ones with a history. #982 renamed
# `LogContext.Fields` to `LogFields` and left four pointers at the old name. #953 merged the two
# cluster directories and left three sentences naming a file that no longer exists. A reader follows
# those pointers to find the other copies, so a stale one is how the names drift next. Both were
# caught by eye, and neither was a wrong field name, so the checks above would have passed.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

COLLECTOR="deploy/clusters/base/collector.yaml"
DOC="docs/ops/PLATFORM_SETUP.md"
KOTLIN=(
    "events-importer/src/main/kotlin/de/norm/events/scraper/LogContext.kt"
    "events-importer/src/main/kotlin/de/norm/events/scraper/LogFields.kt"
    "events-bff/src/main/kotlin/de/norm/events/LogContextConfiguration.kt"
)

problems=0

note() {
    printf '  %s\n' "$1" >&2
    problems=1
}

for f in "$COLLECTOR" "$DOC" "${KOTLIN[@]}"; do
    [ -f "$f" ] || {
        echo "collector-parity: $f is missing; the check cannot run" >&2
        exit 1
    }
done

# Anchored on the declaration, so a name written in KDoc prose is not mistaken for one in force —
# which is where both historical drifts actually lived.
declared() {
    sed -n 's/^[[:space:]]*const val [A-Z_][A-Z0-9_]* = "\([A-Za-z]*\)".*/\1/p' "${KOTLIN[@]}" | sort -u
}

# `httpStatus` is deliberately declared in both modules — a venue's status to us, and ours to a
# browser — so these are compared as sets and never as counts.
lifted_flat() {
    grep -oE 'set\(log\.attributes\["[A-Za-z]+"\], log\.cache\["[A-Za-z]+"\]\)' "$COLLECTOR" |
        sed -E 's/set\(log\.attributes\["([A-Za-z]+)"\], log\.cache\["([A-Za-z]+)"\]\)/\1 \2/' |
        awk '$1 == $2 { print $1 }' | sort -u
}

# The §7 table, as `query-spelling<TAB>written-as` per row. A cell holds more than one name
# (`httpMethod`, `path`), and reads `—` on the rows this repository does not write.
doc_rows() {
    sed -n '/^| Column in OpenObserve/,/^$/p' "$DOC" |
        awk -F'|' 'NR > 2 && NF > 3 {
            query = $2; written = $3
            gsub(/[` ]/, "", query); gsub(/[` ]/, "", written)
            print query "\t" written
        }'
}

# The four sources, with the document narrowed to §7. The rest of PLATFORM_SETUP.md cites paths this
# check has no business failing on.
cited_text() {
    cat "$COLLECTOR" "${KOTLIN[@]}"
    sed -n '/^## 7\. Instrumentation/,/^## 8\./p' "$DOC"
}

# Anchored on this repository's top-level directories, which is what keeps
# `transform/parse_structured_logs` — an OTTL processor, not a path — out of the results.
# shellcheck disable=SC2016  # The backticks are Markdown punctuation, matched literally.
cited_paths() {
    cited_text |
        grep -ohE '`(docs|deploy|events-bff|events-core|events-importer|infra|scripts|\.github)/[A-Za-z0-9_./-]*`' |
        tr -d '`' | sed 's![.,]$!!' | sort -u
}

# Narrowed to the `Log*` family on purpose: those are the names that tell a reader where the other
# copies live, so they are the ones whose staleness costs something. Both spellings count — a
# backticked name in Markdown, and a bracketed KDoc link.
# shellcheck disable=SC2016  # As above.
cited_symbols() {
    cited_text | grep -ohE '(`|\[)Log[A-Za-z]+(\.[A-Za-z_]+)?(`|\])' | tr -d '`[]' | sort -u
}

while read -r name; do
    [ -z "$name" ] && continue
    note "\"$name\" is declared in Kotlin but not lifted in $COLLECTOR — it will never become a column"
done <<< "$(comm -23 <(declared) <(lifted_flat))"

while read -r name; do
    [ -z "$name" ] && continue
    note "\"$name\" is lifted in $COLLECTOR but declared nowhere in Kotlin — a dead rule"
done <<< "$(comm -13 <(declared) <(lifted_flat))"

# One direction only. The table also carries rows for things nothing here writes — `severity`,
# `logger`, `service_version` — and those rows are correct.
while read -r name; do
    [ -z "$name" ] && continue
    row="$(doc_rows | awk -F'\t' -v n="$name" '{ split($2, w, ","); for (i in w) if (w[i] == n) print }')"
    if [ -z "$row" ]; then
        note "\"$name\" has no row in $DOC §7 — an operator cannot know the column exists"
        continue
    fi
    lower="$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]')"
    printf '%s' "$row" | awk -F'\t' -v q="$lower" '
        { split($1, c, ","); for (i in c) if (c[i] == q) found = 1 }
        END { exit found ? 0 : 1 }
    ' || note "$DOC §7 lists \"$name\" but not its lower-case query spelling \"$lower\""
done <<< "$(declared)"

while read -r path; do
    [ -z "$path" ] && continue
    [ -e "$path" ] || note "$path is cited by one of the four sources and does not exist"
done <<< "$(cited_paths)"

while read -r symbol; do
    [ -z "$symbol" ] && continue
    file="${symbol%%.*}"
    member="${symbol#*.}"
    mapfile -t found < <(git ls-files "*/$file.kt")
    if [ "${#found[@]}" -eq 0 ]; then
        note "\"$symbol\" is cited by one of the four sources but no $file.kt exists"
    elif [ "$member" != "$symbol" ] && ! grep -qE "(object|class|val|fun) $member\b" "${found[@]}"; then
        note "\"$symbol\" is cited by one of the four sources but $file.kt declares no $member"
    fi
done <<< "$(cited_symbols)"

if [ "$problems" -ne 0 ]; then
    echo >&2
    echo "Log field names are out of step. See docs/ops/PLATFORM_SETUP.md §7." >&2
    exit 1
fi

printf 'Log fields agree: %s names declared, lifted and documented; every cited path and symbol resolves.\n' \
    "$(declared | grep -c .)"
