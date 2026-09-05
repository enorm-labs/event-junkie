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
#   scripts/version.sh last                              # 0.1.1 — the newest release tag reachable from HEAD
#   scripts/version.sh deserved [--at-least minor|major] # 0.2.0 — what the commits since `last` earn (SemVer)
#
# `compute` defaults to $GITHUB_REF / $GITHUB_SHA and falls back to the working tree, so it produces
# the same answer in CI and on a laptop. The third argument exists for `scripts/version-test.sh`,
# which needs to drive the timestamp rather than read it from a commit.
#
# `set` and `bump` are the only commands that write, and both end by running `check`. The four files
# are edited from one place so that a workflow and a person at a terminal cannot bump them
# differently (#868).
#
# `deserved` reads the Conventional Commits subjects and bodies since the last release and applies
# the rule in docs/ops/RELEASING.md § What a release deserves: a breaking change is a major (a minor
# before 1.0.0), a `feat` is a minor, anything else is a patch. It prints the version and, on stderr,
# the commits that decided it. `cut-release.yml` refuses a tree whose number is not this one.
#
# Requires: yq (for Chart.yaml), git. Reaches no network. VERSION_GIT_ROOT points the history
# commands at another repository, which is how `scripts/version-deserved-test.sh` fabricates one.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GIT_ROOT="${VERSION_GIT_ROOT:-$REPO_ROOT}"
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
  stamp="$(TZ=UTC0 git -C "$GIT_ROOT" show -s --format=%cd --date=format-local:%Y%m%d%H%M%S "$sha" 2>/dev/null)" ||
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
# **The release flow always takes `patch` here.** A snapshot is a prerelease of the coming release,
# and right after a release nothing is known about the next one, so `patch` assumes least. The tree
# is raised later, by `set`, once `deserved` says the commits have earned more.
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

# The newest release tag reachable from HEAD, without its `v`: `0.3.12`.
#
# `--merged HEAD` rather than every tag, so a tag on a branch that never landed cannot become the
# baseline the next release is measured from. Only the bare `vX.Y.Z` shape counts, which is the
# same filter production's OCIRepository applies.
last_release() {
  local tag
  tag="$(git -C "$GIT_ROOT" tag --list 'v[0-9]*' --merged HEAD --sort=-v:refname |
    grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' | head -1)" || true
  [[ -n "$tag" ]] || die "no release tag of the form vX.Y.Z is reachable from HEAD"
  printf '%s\n' "${tag#v}"
}

# Ranks a bump kind so the floor and the commits' verdict can be compared: patch < minor < major.
bump_rank() {
  case "$1" in
    patch) printf '1\n' ;;
    minor) printf '2\n' ;;
    major) printf '3\n' ;;
    *) die "'$1' is not one of patch, minor, major" ;;
  esac
}

# Applies [part] to [base]: `0.3.12 minor` is `0.4.0`.
apply_bump() {
  local base="$1" part="$2" major minor patch
  IFS=. read -r major minor patch <<<"$base"
  case "$part" in
    major) printf '%s.0.0\n' "$((major + 1))" ;;
    minor) printf '%s.%s.0\n' "$major" "$((minor + 1))" ;;
    patch) printf '%s.%s.%s\n' "$major" "$minor" "$((patch + 1))" ;;
  esac
}

# The version the commits since the last release deserve, with the reasoning on stderr.
#
# One `feat` is a minor. One breaking change (`!` in the subject, or a `BREAKING CHANGE:` footer)
# is a major once the last release is 1.0.0 or later, and a minor before that: SemVer §4 says a
# 0.y.z release may change anything, and the minor is the number that signals it. Anything else
# is a patch. A subject that is not Conventional Commits counts as a patch and is listed, so
# an unlabelled feature is visible rather than silently cheap.
#
# `--at-least` is a floor for the one decision the commits cannot show: 1.0.0. It never lowers.
cmd_deserved() {
  local floor="" last
  while (($# > 0)); do
    case "$1" in
      --at-least)
        floor="${2:-}"
        [[ "$floor" == minor || "$floor" == major ]] || die "--at-least takes minor or major"
        shift 2
        ;;
      *) die "usage: version.sh deserved [--at-least minor|major]" ;;
    esac
  done

  last="$(last_release)"
  local -a shas
  mapfile -t shas < <(git -C "$GIT_ROOT" rev-list --no-merges --reverse "v$last..HEAD")
  ((${#shas[@]} > 0)) || die "no commits since v$last, so there is nothing to release"

  local sha subject body type bang kind
  local kind_count_breaking=0 kind_count_feat=0 kind_count_other=0 kind_count_unclassified=0
  local -a deciding=() unclassified=()
  for sha in "${shas[@]}"; do
    subject="$(git -C "$GIT_ROOT" show -s --format=%s "$sha")"
    body="$(git -C "$GIT_ROOT" show -s --format=%b "$sha")"
    if [[ "$subject" =~ ^([a-zA-Z]+)(\([^\)]*\))?(!)?:[[:space:]] ]]; then
      type="${BASH_REMATCH[1],,}"
      bang="${BASH_REMATCH[3]}"
      if [[ -n "$bang" ]] || grep -qE '^BREAKING[ -]CHANGE:' <<<"$body"; then
        kind=breaking
      elif [[ "$type" == feat ]]; then
        kind=feat
      else
        kind=other
      fi
    else
      kind=unclassified
    fi
    case "$kind" in
      breaking)
        kind_count_breaking=$((kind_count_breaking + 1))
        deciding+=("  breaking  ${sha:0:8} $subject")
        ;;
      feat)
        kind_count_feat=$((kind_count_feat + 1))
        deciding+=("  feat      ${sha:0:8} $subject")
        ;;
      other) kind_count_other=$((kind_count_other + 1)) ;;
      unclassified)
        kind_count_unclassified=$((kind_count_unclassified + 1))
        unclassified+=("  ?         ${sha:0:8} $subject")
        ;;
    esac
  done

  local part=patch
  if ((kind_count_breaking > 0)); then
    if [[ "${last%%.*}" == 0 ]]; then part=minor; else part=major; fi
  elif ((kind_count_feat > 0)); then
    part=minor
  fi
  if [[ -n "$floor" ]] && (($(bump_rank "$floor") > $(bump_rank "$part"))); then
    part="$floor"
  fi

  {
    printf 'since v%s: %s commit(s) — %s breaking, %s feat, %s other, %s not Conventional Commits\n' \
      "$last" "${#shas[@]}" "$kind_count_breaking" "$kind_count_feat" "$kind_count_other" "$kind_count_unclassified"
    printf 'deserves: %s' "$part"
    if [[ -n "$floor" && "$floor" == "$part" ]]; then printf ' (floor --at-least %s)' "$floor"; fi
    printf '\n'
    local line
    for line in "${deciding[@]}"; do printf '%s\n' "$line"; done
    for line in "${unclassified[@]}"; do printf '%s\n' "$line"; done
  } >&2

  apply_bump "$last" "$part"
}

cmd_last() {
  last_release
}

cmd_base() {
  base_version
}

cmd_compute() {
  local ref="${1:-${GITHUB_REF:-}}" sha="${2:-${GITHUB_SHA:-}}" stamp="${3:-}"
  [[ -n "$ref" ]] || ref="$(git -C "$GIT_ROOT" rev-parse --symbolic-full-name HEAD)"
  [[ -n "$sha" ]] || sha="$(git -C "$GIT_ROOT" rev-parse HEAD)"

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
    last) cmd_last ;;
    deserved) cmd_deserved "$@" ;;
    *)
      printf 'usage: version.sh {base|compute [ref] [sha]|check|next <part>|set <x.y.z>|bump <part>|last|deserved [--at-least minor|major]}\n' >&2
      exit 2
      ;;
  esac
}

main "$@"
