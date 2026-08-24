#!/usr/bin/env bash
#
# format-markdown.sh — oxfmt over the repository's Markdown, and only its Markdown.
#
# Usage:
#   scripts/format-markdown.sh              # format every tracked .md in place
#   scripts/format-markdown.sh check        # report drift, write nothing (CI / pre-push)
#   scripts/format-markdown.sh [check] F... # restrict to the given files (pre-commit passes these)
#
# Reaches no network. In `check` mode it writes nothing at all.
#
# Three things about this script are deliberate, and each one cost an experiment to establish:
#
# 1. It uses the oxfmt pinned in events-frontend/package.json, never the one on $PATH. oxfmt is
#    pre-1.0 and its Markdown output is not stable across versions; whichever binary runs in a commit
#    hook has to be the one CI runs, or `check` fails depending on whose laptop touched the file
#    last. package-lock.json is what makes that reproducible; Homebrew upgrades out from under you.
#
#    Related, and non-obvious: **oxfmt reads .editorconfig**. The `[*] indent_size = 4` there is what
#    makes nested list items indent by four spaces; without it oxfmt uses its own default and the
#    output differs. Measuring oxfmt in a scratch directory does not reproduce what it does here
#    unless .editorconfig is copied alongside.
#
# 2. `--disable-nested-config`, because oxfmt's nested configs *replace* rather than merge. Without
#    it, events-frontend/.oxfmtrc.json shadows the root config wholesale for events-frontend/*.md, so
#    those two files format to different settings than every other. The flag is safe only because
#    this script never passes oxfmt anything but Markdown.
#
# 3. Write mode runs oxfmt twice, and always will. A table indented under a list item is skipped on
#    the first pass and only formatted on the second; it converges at pass two and stays there. One
#    pass would leave such a file off its own fixpoint, and `check` would then fail on a file the
#    formatter had just written.
#
#    Do not remove the second run on a version bump. This is intended Prettier-compatible behaviour,
#    not a bug being waited out: Prettier needs the same two passes, oxfmt targets Prettier, and
#    upstream closed oxc-project/oxc#25612 as `not planned` on exactly that basis. `AGENTS.md` is the
#    file here that exhibits the shape, so dropping the second run fails `check` immediately.
#
# Scope is enforced twice over, here and in .oxfmtrc.json's ignorePatterns. oxfmt also claims YAML,
# JSON, CSS and TS, and the Go-templated YAML under deploy/charts/ is exactly what it cannot parse —
# same reason .pre-commit-config.yaml refuses to grow a check-yaml hook.

set -euo pipefail

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

# oxfmt rejects any path containing '..', so everything runs from the repository root and the
# arguments stay relative to it. With no arguments, the glob is quoted so oxfmt expands it rather
# than the shell — node_modules and anything .gitignored (build/BACKLOG.md) are skipped by default.
cd "$REPO_ROOT"

targets=()
if [[ $# -gt 0 ]]; then
  for f in "$@"; do
    [[ $f == *.md ]] && targets+=("$f")
  done
  # pre-commit fires the hook on a staged .editorconfig or .oxfmtrc.json too; with no .md among the
  # filenames there is nothing to do, and an empty argument list would mean "the whole repository".
  [[ ${#targets[@]} -gt 0 ]] || exit 0
else
  targets=('**/*.md')
fi

if [[ $mode == check ]]; then
  exec "$OXFMT" --disable-nested-config --check --no-error-on-unmatched-pattern "${targets[@]}"
fi

"$OXFMT" --disable-nested-config --no-error-on-unmatched-pattern "${targets[@]}" >/dev/null
"$OXFMT" --disable-nested-config --no-error-on-unmatched-pattern "${targets[@]}"
