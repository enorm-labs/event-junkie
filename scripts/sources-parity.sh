#!/usr/bin/env bash
#
# sources-parity.sh — the counts in EVENT_DATA_SOURCES.md's status table, against the rows of the tables they name.
#
# Usage:
#   scripts/sources-parity.sh                        # exits 1 listing whatever is out of step
#   SOURCES_DOC=<file> scripts/sources-parity.sh     # the same, for another copy of the document
#
# Reaches no network and writes nothing.
#
# The status table under "The short version" gives a count per section. Each row links to its
# section, and the count is the number of data rows in the tables under that `##` heading. Parallel
# importer PRs write identical edits to the count column, git merges identical hunks silently, and
# the counts went stale by up to eleven rows (#2510). The sections are not listed here: a new `##`
# table is checked as soon as the status table has a row that links to it.

set -euo pipefail

case "${1:-}" in
    -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DOC="${SOURCES_DOC:-$REPO_ROOT/docs/EVENT_DATA_SOURCES.md}"

[ -f "$DOC" ] || {
    echo "sources-parity: $DOC is missing; the check cannot run" >&2
    exit 1
}

# An anchor is reduced to ASCII letters, digits, `_` and `-` on both sides, so an emoji in a heading
# and the leading `-` GitHub leaves in its place compare equal without a Unicode table.
LC_ALL=C awk '
    function anchor(s) {
        s = tolower(s)
        gsub(/ /, "-", s)
        gsub(/[^a-z0-9_-]/, "", s)
        return s
    }
    function cell(line, n,    parts) {
        split(line, parts, "|")
        gsub(/^ +| +$/, "", parts[n + 1])
        return parts[n + 1]
    }

    /^```/ { fenced = !fenced }
    fenced { prev = $0; next }

    /^## / {
        section = substr($0, 4)
        key = anchor(section)
        heading[key] = section
        rows[key] += 0
    }

    /^\|/ {
        if (prev !~ /^\|/) {
            # A header row. The first one whose first cell is "Status" is the status table.
            status = (statuses == 0 && cell($0, 1) == "Status")
            if (status) {
                for (i = 2; cell($0, i) != ""; i++) if (cell($0, i) == "Count") countcol = i
            }
        } else if ($0 !~ /^\|[ :|-]+\|$/) {
            if (status) {
                link = cell($0, 1)
                if (match(link, /\(#[^)]*\)/)) {
                    statuses++
                    target[statuses] = anchor(substr(link, RSTART + 2, RLENGTH - 3))
                    label[statuses] = link
                    stated[statuses] = cell($0, countcol)
                } else {
                    printf "  status row \"%s\" links to no section\n", link > "/dev/stderr"
                    bad = 1
                }
            } else {
                rows[key]++
            }
        }
    }

    { prev = $0 }

    END {
        if (statuses == 0 || countcol == "") {
            print "  no status table with a Count column: the check cannot read the document" > "/dev/stderr"
            exit 1
        }
        for (i = 1; i <= statuses; i++) {
            t = target[i]
            if (!(t in heading)) {
                printf "  %s links to #%s, and no ## heading has that anchor\n", label[i], t > "/dev/stderr"
                bad = 1
            } else if (stated[i] != rows[t] "") {
                printf "  %s says %s, and \"## %s\" holds %d rows\n", label[i], stated[i], heading[t], rows[t] > "/dev/stderr"
                bad = 1
            } else {
                summary = summary (summary == "" ? "" : ", ") heading[t] " " rows[t]
            }
        }
        if (bad) exit 1
        printf "Source counts agree: %s.\n", summary
    }
' "$DOC" || {
    echo >&2
    echo "The status table in ${DOC#"$REPO_ROOT"/} is out of step. Write the row counts into its Count column." >&2
    exit 1
}
