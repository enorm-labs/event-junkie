#!/usr/bin/env bash
#
# version.sh — the one place that knows what version this commit is.
#
# `gradle.properties` carries `version=X.Y.Z-SNAPSHOT` and is the single source of truth; everything
# else derives from it. Three other files repeat the number and none of them is authoritative:
# `events-frontend/package.json` (npm has no -SNAPSHOT convention, so it holds the bare X.Y.Z) and
# the chart's `version` and `appVersion` (placeholders — the release workflow stamps the computed
# version over them before packaging).
#
# Usage:
#   scripts/version.sh base                              # 0.1.1 — the released number this tree is heading for
#   scripts/version.sh compute [ref] [sha] [timestamp]   # 0.1.1-snapshot.20260814122042.g33fd32g, or 0.1.1 from refs/tags/v0.1.1
#   scripts/version.sh check                             # fails if the four places disagree
#   scripts/version.sh next <patch|minor|major>          # 0.1.2 — the next base, printed, nothing written
#   scripts/version.sh set <x.y.z>                       # write that version to all four files
#   scripts/version.sh bump <patch|minor|major>          # next + set, which is the post-release bump
#
# `compute` defaults to $GITHUB_REF / $GITHUB_SHA and falls back to the working tree, so it produces
# the same answer in CI and on a laptop. The third argument exists for `scripts/version-test.sh`,
# which needs to drive the timestamp rather than read it from a commit.
#
# `set` and `bump` are the only commands that write, and both end by running `check`. The four files
# are edited from one place so that a workflow and a person at a terminal cannot bump them
# differently (#868).
#
# Requires: yq (for Chart.yaml). Reaches no network.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_PROPERTIES="$REPO_ROOT/gradle.properties"
PACKAGE_JSON="$REPO_ROOT/events-frontend/package.json"
CHART_YAML="$REPO_ROOT/deploy/charts/event-junkie/Chart.yaml"

die() {
  printf 'version.sh: %s\n' "$1" >&2
  exit 1
}

# The declared version, suffix and all: `0.1.1-SNAPSHOT`.
declared_version() {
  local value
  value="$(sed -n 's/^version=//p' "$GRADLE_PROPERTIES" | head -1)"
  [[ -n "$value" ]] || die "no 'version=' line in gradle.properties"
  printf '%s\n' "$value"
}

# The released number this tree is heading for: `0.1.1`.
#
# `main` always carries -SNAPSHOT — a release version is never committed, it is supplied by the tag
# via `-Pversion=`. So the suffix being missing means someone hand-edited a release number into the
# file, which would make every subsequent snapshot claim to be a release.
base_version() {
  local declared
  declared="$(declared_version)"
  [[ "$declared" == *-SNAPSHOT ]] ||
    die "gradle.properties says '$declared'; it must end in -SNAPSHOT (a release version comes from the tag, not the file)"
  local base="${declared%-SNAPSHOT}"
  [[ "$base" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    die "'$base' is not a three-part SemVer version"
  printf '%s\n' "$base"
}

# The top-level "version" key. Anchored to exactly two spaces of indentation so a nested "version"
# in a dependency block cannot match — package.json's top-level keys are the only ones at that
# depth, and this avoids depending on node or jq being installed.
package_json_version() {
  local value
  value="$(sed -n 's/^  "version": "\(.*\)",\{0,1\}$/\1/p' "$PACKAGE_JSON" | head -1)"
  [[ -n "$value" ]] || die "no top-level \"version\" key in events-frontend/package.json"
  printf '%s\n' "$value"
}

# The commit's own committer date, in UTC, as `YYYYMMDDHHMMSS`: `20260814122042`.
#
# The committer date rather than the author date, because that is when the commit landed on `main` —
# a squash or rebase merge stamps it at merge time, so it increases in the order snapshots are
# published. The author date is when the branch was written, which can be weeks earlier and is not
# ordered by anything.
#
# The commit's date rather than `date -u`, because `compute` has to stay a pure function of the
# commit. Re-running release.yml on the same sha must produce the same version — otherwise a re-run
# publishes a second, differently-named copy of identical artifacts — and CI and a laptop must agree
# about the same commit, which `date -u` cannot do by construction.
commit_timestamp() {
  local sha="$1" stamp
  stamp="$(TZ=UTC0 git -C "$REPO_ROOT" show -s --format=%cd --date=format-local:%Y%m%d%H%M%S "$sha" 2>/dev/null)" ||
    die "cannot read the committer date of '$sha' — it is not a commit in this repository"
  # Fourteen digits, and the first one is not a zero: a SemVer numeric identifier must not carry a
  # leading zero, and the whole point of this identifier is that it compares numerically.
  [[ "$stamp" =~ ^[1-9][0-9]{13}$ ]] ||
    die "committer date of '$sha' produced '$stamp', which is not a 14-digit timestamp"
  printf '%s\n' "$stamp"
}

# Replaces the one line matching [pattern] in [file], and fails if it matched nothing.
#
# `sed -i` is not portable — BSD sed demands an argument that GNU sed reads as the next expression —
# so the file is rewritten through a temporary. A silent no-match is the failure worth naming: every
# caller here would then leave the file at its old version while reporting success.
replace_line() {
  local file="$1" pattern="$2" replacement="$3" tmp
  grep -qE "$pattern" "$file" || die "no line matching /$pattern/ in $file"
  tmp="$(mktemp)"
  sed -E "s|$pattern|$replacement|" "$file" >"$tmp"
  # Copied back rather than moved, so the file keeps its own mode. `mktemp` creates at 600, and a
  # `mv` would carry that onto a file the whole repository reads.
  cat "$tmp" >"$file"
  rm -f "$tmp"
}

# The next base version after this tree's, for `patch`, `minor` or `major`.
#
# **`patch` is the default the release flow uses.** A snapshot is a prerelease of the coming release,
# so bumping to `0.4.0-SNAPSHOT` commits the next release to being a minor one before anybody knows
# what is in it. `patch` assumes least; the other two are there for a release that has earned one.
next_version() {
  local part="$1" base major minor patch
  base="$(base_version)"
  IFS=. read -r major minor patch <<<"$base"

  case "$part" in
    major) printf '%s.0.0\n' "$((major + 1))" ;;
    minor) printf '%s.%s.0\n' "$major" "$((minor + 1))" ;;
    patch) printf '%s.%s.%s\n' "$major" "$minor" "$((patch + 1))" ;;
    *) die "'$part' is not one of patch, minor, major" ;;
  esac
}

cmd_next() {
  local part="${1:-}"
  [[ -n "$part" ]] || die "usage: version.sh next <patch|minor|major>"
  next_version "$part"
}

# Writes [version] to all four files, then proves it landed.
#
# The suffix is added here rather than passed in, so a caller cannot write a release number into
# `gradle.properties` — which `base_version` refuses to read and which would make every later
# snapshot claim to be a release.
cmd_set() {
  local version="${1:-}"
  [[ "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    die "'$version' is not a three-part SemVer version"

  replace_line "$GRADLE_PROPERTIES" '^version=.*$' "version=$version-SNAPSHOT"
  # The same two-space anchor `package_json_version` reads, and the trailing comma is kept because
  # `version` is not the last key.
  replace_line "$PACKAGE_JSON" '^  "version": ".*",$' "  \"version\": \"$version\","
  replace_line "$CHART_YAML" '^version: .*$' "version: $version"
  # Quoted, because an unquoted `appVersion: 1.10` is a YAML float and loses its trailing zero.
  replace_line "$CHART_YAML" '^appVersion: .*$' "appVersion: \"$version\""

  # `check` compares the other three against gradle.properties, so four files left untouched would
  # still agree with each other and pass. This is the assertion that the write happened at all.
  [[ "$(base_version)" == "$version" ]] ||
    die "gradle.properties still says $(declared_version) after setting $version"

  cmd_check
}

cmd_bump() {
  local part="${1:-}"
  [[ -n "$part" ]] || die "usage: version.sh bump <patch|minor|major>"
  cmd_set "$(next_version "$part")"
}

cmd_base() {
  base_version
}

cmd_compute() {
  local ref="${1:-${GITHUB_REF:-}}" sha="${2:-${GITHUB_SHA:-}}" stamp="${3:-}"
  [[ -n "$ref" ]] || ref="$(git -C "$REPO_ROOT" rev-parse --symbolic-full-name HEAD)"
  [[ -n "$sha" ]] || sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"

  local base
  base="$(base_version)"

  if [[ "$ref" == refs/tags/* ]]; then
    local tag="${ref#refs/tags/}"
    [[ "$tag" == v* ]] || die "release tag '$tag' must be of the form v<major>.<minor>.<patch>"
    local tagged="${tag#v}"
    # The guard that stops a tag inventing a version. Cutting v0.2.0 from a tree whose
    # gradle.properties still says 0.1.1-SNAPSHOT would publish images whose /actuator/info
    # disagrees with their own tag, and there is no later step that would notice.
    [[ "$tagged" == "$base" ]] ||
      die "tag '$tag' does not match gradle.properties ($base-SNAPSHOT); bump the file first, then tag"
    printf '%s\n' "$tagged"
    return
  fi

  # A prerelease *of* the coming release, never of the last one: SemVer sorts
  # 0.1.1-snapshot.20260814122042.g33fd32g before 0.1.1, so naming a snapshot after the released
  # version would have it claim to be older than code it is newer than. Maven's -SNAPSHOT semantics.
  #
  # THE TIMESTAMP IS THE ORDERING, AND IT IS THE WHOLE REASON THIS IDENTIFIER EXISTS (#455). SemVer
  # §11 compares prerelease identifiers field by field, numerically for an all-digit identifier and
  # lexically in ASCII for one containing a letter. A short sha is effectively random, so a
  # `0.1.0-snapshot.g<sha>` scheme gives staging's `semver: ">=0.0.0-0"` range no way to mean "the
  # newest chart" — it means "whichever sha sorts highest", a different chart on most days, and it
  # can move backwards. Observed on staging: ten snapshots published, Flux resolved the sixth-oldest
  # because `f` > `d`. A 14-digit UTC timestamp is numeric, so it orders, and it stays legible in a
  # `helm list`, which `github.run_number` is not.
  #
  # **The `g` prefix is load-bearing, for one reason:** a numeric SemVer identifier must not carry a
  # leading zero, so a short sha like `0031234` produces a version string `helm lint --strict`
  # rejects outright — about one commit in 270, since a sha is uniform over hex. Starting the
  # identifier with a letter makes it alphanumeric, where the rule does not apply.
  #
  # It does NOT help the ordering, and the opposite is easy to assume. Identifiers compare left to
  # right and stop at the first difference, so the timestamp decides everything and the sha is
  # reached only on a same-second tie. There `g` buys one thing: SemVer ranks every numeric
  # identifier below every non-numeric one, so a bare all-digit sha would always lose a tie
  # regardless of its value. Both are arbitrary; consistently arbitrary is better.
  [[ -n "$stamp" ]] || stamp="$(commit_timestamp "$sha")"
  printf '%s-snapshot.%s.g%s\n' "$base" "$stamp" "${sha:0:7}"
}

cmd_check() {
  command -v yq >/dev/null || die "yq is not installed"

  local base status=0
  base="$(base_version)"

  local pkg chart_version chart_app_version
  pkg="$(package_json_version)"
  chart_version="$(yq -N '.version' "$CHART_YAML")"
  chart_app_version="$(yq -N '.appVersion' "$CHART_YAML")"

  compare() {
    local what="$1" actual="$2"
    if [[ "$actual" == "$base" ]]; then
      printf '  ok    %s = %s\n' "$what" "$actual"
    else
      printf '  FAIL  %s = %s, expected %s\n' "$what" "$actual" "$base" >&2
      status=1
    fi
  }

  printf 'gradle.properties declares %s, so every other file must say %s\n' "$(declared_version)" "$base"
  compare "events-frontend/package.json  version" "$pkg"
  compare "Chart.yaml                    version" "$chart_version"
  compare "Chart.yaml                    appVersion" "$chart_app_version"

  if ((status != 0)); then
    printf '\n%s\n%s\n%s\n' \
      'The chart values are placeholders that the release workflow stamps over, but they are' \
      'still what a helm install from a checkout uses — so they have to be right. Bump all' \
      'four together, or none.' >&2
    exit 1
  fi
}

main() {
  local command="${1:-}"
  shift || true

  case "$command" in
    base) cmd_base ;;
    compute) cmd_compute "$@" ;;
    check) cmd_check ;;
    next) cmd_next "$@" ;;
    set) cmd_set "$@" ;;
    bump) cmd_bump "$@" ;;
    *)
      printf 'usage: version.sh {base|compute [ref] [sha]|check|next <part>|set <x.y.z>|bump <part>}\n' >&2
      exit 2
      ;;
  esac
}

main "$@"
