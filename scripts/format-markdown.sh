#!/usr/bin/env bash
#
# format-markdown.sh — oxfmt over the repository's Markdown, and only its Markdown.
#
# Usage:
#   scripts/format-markdown.sh              # format every tracked .md in place
#   scripts/format-markdown.sh check        # report drift, write nothing (CI / pre-push)
#   scripts/format-markdown.sh [check] F... # only these files, relative to the current directory
#
# Reaches no network. In `check` mode it writes nothing at all. Four things are deliberate, each
# established by experiment:
#
# 1. The oxfmt pinned in events-frontend/package.json, never the one on $PATH: oxfmt is pre-1.0 and
#    its Markdown output is not stable across versions, so a commit hook running a different binary
#    from CI fails `check` depending on whose laptop touched the file last. Non-obvious with it:
#    **oxfmt reads .editorconfig**, whose `[*] indent_size = 4` indents nested list items by four —
#    so measuring oxfmt in a scratch directory reproduces nothing without that file alongside.
#
# 2. `--disable-nested-config`, because oxfmt's nested configs *replace* rather than merge: without
#    it events-frontend/.oxfmtrc.json shadows the root config wholesale for events-frontend/*.md.
#    Safe only because this script never passes oxfmt anything but Markdown.
#
# 3. Write mode runs oxfmt twice, and always will: a table indented under a list item is skipped on
#    the first pass and formatted on the second, so one pass leaves such a file off its own fixpoint
#    and `check` then fails on a file the formatter just wrote. **Do not remove the second run on a
#    version bump** — Prettier needs the same two passes, oxfmt targets Prettier, and upstream closed
#    oxc-project/oxc#25612 as `not planned` on that basis. `AGENTS.md` exhibits the shape.
#
# 4. A named file is resolved against the caller's directory before the `cd` to the repository
#    root, which oxfmt needs because it rejects any path containing '..'; otherwise a relative name
#    would check the root's file of that name, or nothing, and exit 0 (#580). A named file that
#    does not exist, or lies outside this checkout, is an error: another clone
#    has its own copy of this script, and only this checkout's .editorconfig formats like CI.
#    `--no-error-on-unmatched-pattern` stays on for named files too, because after those checks
#    the only file that matches nothing is one ignorePatterns excludes, such as a vendored skill
#    the hook passes on a refresh.
#
# Scope is enforced twice, here and in .oxfmtrc.json's ignorePatterns: oxfmt also claims YAML, JSON,
# CSS and TS, and the Go-templated YAML under deploy/charts/ is exactly what it cannot parse — same
# reason .pre-commit-config.yaml refuses to grow a check-yaml hook.

set -euo pipefail

case "${1:-}" in
  -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OXFMT="$REPO_ROOT/events-frontend/node_modules/.bin/oxfmt"

die() {
  printf 'format-markdown.sh: %s\n' "$1" >&2
  exit 1
}

[[ -x "$OXFMT" ]] || die "no oxfmt at $OXFMT — run 'npm ci' in events-frontend/ first"

mode=format
if [[ ${1:-} == check ]]; then
  mode=check
  shift
fi

ROOT_REAL="$(cd "$REPO_ROOT" && pwd -P)"

targets=()
if [[ $# -gt 0 ]]; then
  for f in "$@"; do
    [[ $f == *.md ]] || continue
    [[ -f $f ]] || die "$f: no such file (relative to $PWD)"
    abs="$(cd "$(dirname "$f")" && pwd -P)/$(basename "$f")"
    [[ $abs == "$ROOT_REAL"/* ]] || die "$f is outside $ROOT_REAL — run that checkout's own scripts/format-markdown.sh"
    targets+=("${abs#"$ROOT_REAL"/}")
  done
  # pre-commit fires the hook on a staged .editorconfig or .oxfmtrc.json too; with no .md among the
  # filenames there is nothing to do, and an empty argument list would mean "the whole repository".
  [[ ${#targets[@]} -gt 0 ]] || exit 0
else
  targets=('**/*.md')
fi

# With no arguments, the glob is quoted so oxfmt expands it rather than the shell — node_modules and
# anything .gitignored (build/BACKLOG.md) are skipped by default.
cd "$REPO_ROOT"

if [[ $mode == check ]]; then
  exec "$OXFMT" --disable-nested-config --check --no-error-on-unmatched-pattern "${targets[@]}"
fi

"$OXFMT" --disable-nested-config --no-error-on-unmatched-pattern "${targets[@]}" >/dev/null
"$OXFMT" --disable-nested-config --no-error-on-unmatched-pattern "${targets[@]}"
