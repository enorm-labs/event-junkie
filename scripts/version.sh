#!/usr/bin/env bash
#
# version.sh — the one place that knows what version this commit is.
#
# No file in the tree carries the version (ADR-032). A commit's number is read from the history: the
# newest release tag reachable from it, and the Conventional Commits since that tag, by the rule in
# docs/ops/RELEASING.md § What a release deserves. `gradle.properties`, `events-frontend/package.json`
# and the chart's `version` / `appVersion` hold `0.0.0` placeholders that every build stamps over.
#
# Usage:
#   scripts/version.sh base                              # 0.1.1 — the number the commits since the last release earn
#   scripts/version.sh compute [ref] [sha] [timestamp]   # 0.1.1-snapshot.20260814122042.g33fd32g, or 0.1.1 from refs/tags/v0.1.1
#   scripts/version.sh last                              # 0.1.0 — the newest release tag reachable from HEAD
#   scripts/version.sh deserved [--at-least minor|major] # 0.2.0 — what the commits since `last` earn, with the reasoning on stderr
#
# `compute` defaults to $GITHUB_REF / $GITHUB_SHA and falls back to the working tree, so it produces
# the same answer in CI and on a laptop. The third argument exists for `scripts/version-test.sh`,
# which needs to drive the timestamp rather than read it from a commit. On a tag it checks the tag
# against the commits and refuses one that claims another number, which is how a release number is
# never typed (#868). With no release tag in reach it prints `0.0.0-local`, so a shallow clone still
# builds; `deserved` dies instead, because a cut must not guess.
#
# `deserved` reads the Conventional Commits subjects and bodies since the last release and applies
# the rule: a breaking change is a major (a minor before 1.0.0), a `feat` in a product scope is a
# minor, anything else is a patch. It prints the version and, on stderr, the commits that decided it.
# `cut-release.yml` tags what it says.
#
# The scope list is the one `label-pr.yml` goes red on, and it is here as well as there because the
# labeller guards a title on its way in and this reads the history as it is: two `feat` commits
# outside it landed on `main` in the hours between the labeller's rule and its becoming a required
# check, and would have made 0.18.0 of a cycle in which nothing on the site changed.
#
# Requires: git. Reaches no network. VERSION_GIT_ROOT points the history commands at another
# repository, which is how `scripts/version-deserved-test.sh` fabricates one.

set -euo pipefail

case "${1:-}" in
  -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GIT_ROOT="${VERSION_GIT_ROOT:-$REPO_ROOT}"

die() {
  printf 'version.sh: %s\n' "$1" >&2
  exit 1
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

# The newest release tag reachable from HEAD, without its `v`: `0.3.12`.
#
# `--merged HEAD` rather than every tag, so a tag on a branch that never landed cannot become the
# baseline the next release is measured from. Only the bare `vX.Y.Z` shape counts, which is the
# same filter production's OCIRepository applies.
last_release() {
  find_last_release "${1:-}" || die "no release tag of the form vX.Y.Z is reachable from HEAD"
}

# Prints the newest release tag reachable from HEAD, or returns 1 with no message.
find_last_release() {
  local exclude_head="${1:-}" tag
  tag="$(git -C "$GIT_ROOT" tag --list 'v[0-9]*' --merged HEAD --sort=-v:refname |
    grep -E '^v[0-9]+\.[0-9]+\.[0-9]+$' |
    if [[ "$exclude_head" == before-head ]]; then
      grep -vxF -f <(git -C "$GIT_ROOT" tag --points-at HEAD) || true
    else
      cat
    fi |
    head -1)" || true
  [[ -n "$tag" ]] || return 1
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
  local floor=""
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
  deserved_version "$floor" cut
}

# deserved_version <floor> <cut|snapshot|tag>
#
# The rule, once. `cut` refuses a tree with nothing to release. `snapshot` names the patch after the
# last release when nothing landed yet, so a build at the tagged commit still has a number. `tag`
# measures from the release before the one on HEAD, because the tag on HEAD is the thing being checked.
deserved_version() {
  local floor="$1" mode="$2" last
  if [[ "$mode" == tag ]]; then
    last="$(last_release before-head)" || return 1
  else
    last="$(last_release)" || return 1
  fi
  local -a shas
  mapfile -t shas < <(git -C "$GIT_ROOT" rev-list --no-merges --reverse "v$last..HEAD")
  if ((${#shas[@]} == 0)); then
    [[ "$mode" == snapshot ]] || die "no commits since v$last, so there is nothing to release"
    apply_bump "$last" patch
    return
  fi

  # What a visitor to the site can see. A `feat` elsewhere is a change to the pipeline, the chart,
  # a script or an agent, and earns what any other such change earns: a patch.
  local product_scopes=" frontend events promoters venues artists importer scraper bff images branding "

  local sha subject body type scope bang kind
  local kind_count_breaking=0 kind_count_feat=0 kind_count_other=0 kind_count_unclassified=0
  local -a deciding=() unclassified=()
  for sha in "${shas[@]}"; do
    subject="$(git -C "$GIT_ROOT" show -s --format=%s "$sha")"
    body="$(git -C "$GIT_ROOT" show -s --format=%b "$sha")"
    if [[ "$subject" =~ ^([a-zA-Z]+)(\(([^\)]*)\))?(!)?:[[:space:]] ]]; then
      type="${BASH_REMATCH[1],,}"
      scope="${BASH_REMATCH[3],,}"
      bang="${BASH_REMATCH[4]}"
      if [[ -n "$bang" ]] || grep -qE '^BREAKING[ -]CHANGE:' <<<"$body"; then
        kind=breaking
      elif [[ "$type" == feat && "$product_scopes" == *" $scope "* ]]; then
        kind=feat
      elif [[ "$type" == feat ]]; then
        # Listed, so the summary shows the `feat` that did not move the number and why.
        kind=other
        deciding+=("  patch     ${sha:0:8} $subject  (feat outside a product scope)")
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

cmd_compute() {
  local ref="${1:-${GITHUB_REF:-}}" sha="${2:-${GITHUB_SHA:-}}" stamp="${3:-}"
  [[ -n "$ref" ]] || ref="$(git -C "$GIT_ROOT" rev-parse --symbolic-full-name HEAD)"
  [[ -n "$sha" ]] || sha="$(git -C "$GIT_ROOT" rev-parse HEAD)"

  if [[ "$ref" == refs/tags/* ]]; then
    local tag="${ref#refs/tags/}"
    [[ "$tag" == v* ]] || die "release tag '$tag' must be of the form v<major>.<minor>.<patch>"
    local tagged="${tag#v}" expected
    [[ "$tagged" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "release tag '$tag' must be of the form v<major>.<minor>.<patch>"
    # The first release ever has nothing to be measured from; every later one is checked.
    if find_last_release before-head >/dev/null; then
      expected="$(deserved_version "" tag 2>/dev/null)" || die "cannot read what the commits before '$tag' deserve"
      [[ "$tagged" == "$expected" ]] ||
        die "tag '$tag' does not match what the commits deserve ($expected); a release number is read, never typed"
    fi
    printf '%s\n' "$tagged"
    return
  fi

  local base
  if ! base="$(deserved_version "" snapshot 2>/dev/null)"; then
    printf '0.0.0-local\n'
    return
  fi
  [[ -n "$stamp" ]] || stamp="$(commit_timestamp "$sha")"
  printf '%s-snapshot.%s.g%s\n' "$base" "$stamp" "${sha:0:7}"
}

cmd_base() {
  deserved_version "" snapshot 2>/dev/null
}

main() {
  local command="${1:-}"
  shift || true

  case "$command" in
    base) cmd_base ;;
    compute) cmd_compute "$@" ;;
    last) cmd_last ;;
    deserved) cmd_deserved "$@" ;;
    *)
      printf 'usage: version.sh {base|compute [ref] [sha] [timestamp]|last|deserved [--at-least minor|major]}\n' >&2
      exit 2
      ;;
  esac
}

main "$@"
