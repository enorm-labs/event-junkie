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
#   scripts/version.sh base                  # 0.1.0             — the released number this tree is heading for
#   scripts/version.sh compute [ref] [sha]   # 0.1.0-snapshot.g33fd32g, or 0.1.0 from refs/tags/v0.1.0
#   scripts/version.sh check                 # fails if the four places disagree
#
# `compute` defaults to $GITHUB_REF / $GITHUB_SHA and falls back to the working tree, so it produces
# the same answer in CI and on a laptop.
#
# Requires: yq (for Chart.yaml). Reaches no network and writes nothing.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
GRADLE_PROPERTIES="$REPO_ROOT/gradle.properties"
PACKAGE_JSON="$REPO_ROOT/events-frontend/package.json"
CHART_YAML="$REPO_ROOT/deploy/charts/event-junkie/Chart.yaml"

die() {
  printf 'version.sh: %s\n' "$1" >&2
  exit 1
}

# The declared version, suffix and all: `0.1.0-SNAPSHOT`.
declared_version() {
  local value
  value="$(sed -n 's/^version=//p' "$GRADLE_PROPERTIES" | head -1)"
  [[ -n "$value" ]] || die "no 'version=' line in gradle.properties"
  printf '%s\n' "$value"
}

# The released number this tree is heading for: `0.1.0`.
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

cmd_base() {
  base_version
}

cmd_compute() {
  local ref="${1:-${GITHUB_REF:-}}" sha="${2:-${GITHUB_SHA:-}}"
  [[ -n "$ref" ]] || ref="$(git -C "$REPO_ROOT" rev-parse --symbolic-full-name HEAD)"
  [[ -n "$sha" ]] || sha="$(git -C "$REPO_ROOT" rev-parse HEAD)"

  local base
  base="$(base_version)"

  if [[ "$ref" == refs/tags/* ]]; then
    local tag="${ref#refs/tags/}"
    [[ "$tag" == v* ]] || die "release tag '$tag' must be of the form v<major>.<minor>.<patch>"
    local tagged="${tag#v}"
    # The guard that stops a tag inventing a version. Cutting v0.2.0 from a tree whose
    # gradle.properties still says 0.1.0-SNAPSHOT would publish images whose /actuator/info
    # disagrees with their own tag, and there is no later step that would notice.
    [[ "$tagged" == "$base" ]] ||
      die "tag '$tag' does not match gradle.properties ($base-SNAPSHOT); bump the file first, then tag"
    printf '%s\n' "$tagged"
    return
  fi

  # A prerelease *of* the coming release, never of the last one: SemVer sorts
  # 0.1.0-snapshot.g33fd32g before 0.1.0, so naming a snapshot after the released version would have
  # it claim to be older than code it is newer than. Same semantics as Maven's -SNAPSHOT.
  #
  # The `g` prefix is git-describe's convention and it is load-bearing here rather than cosmetic: a
  # SemVer prerelease identifier made only of digits must not carry a leading zero, so a short sha
  # like `0031234` would produce a version string that `helm lint --strict` rejects. Roughly one
  # commit in four hundred. Starting the identifier with a letter makes it alphanumeric, which has
  # no such rule.
  printf '%s-snapshot.g%s\n' "$base" "${sha:0:7}"
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
    *)
      printf 'usage: version.sh {base|compute [ref] [sha]|check}\n' >&2
      exit 2
      ;;
  esac
}

main "$@"
