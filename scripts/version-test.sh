#!/usr/bin/env bash
#
# version-test.sh — assert that snapshot versions ORDER, not merely that they parse.
#
# `scripts/version.sh` has always produced valid SemVer and `helm lint --strict` has always accepted
# it. That was the problem (#455): `0.1.0-snapshot.g<sha>` is well-formed and orders by a short sha,
# so staging's `>=0.0.0-0` resolved whichever tag sorted highest in ASCII, silently, for three days.
# A format assertion is exactly the test that would NOT have caught it; these publish a set of
# versions in a known order and demand that the range resolves to the LAST one.
#
# The resolver is Helm's own — the Masterminds library source-controller embeds — through `helm
# search repo` against a fabricated repository index in a temp dir. No network, no cluster.
#
# Usage: scripts/version-test.sh [chart-dir]
#
# Requires: helm, yq, git. Reaches no network and writes only under a temp dir it removes.

set -euo pipefail

case "${1:-}" in
  -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION_SH="$REPO_ROOT/scripts/version.sh"
CHART_DIR="${1:-$REPO_ROOT/deploy/charts/event-junkie}"

# The two ranges that decide what each environment runs, copied from the OCIRepositories. If either
# file's range changes, these change with it.
STAGING_RANGE=">=0.0.0-0"
PRODUCTION_RANGE=">=0.1.0"

# The base values.yaml cannot lint alone; the same two overrides validate-chart.yml passes.
BASE_OVERRIDES=(--set "database.host=10.0.1.2" --set "database.existingSecret=events-db")

failures=0

fail() {
  printf '  FAIL  %s\n' "$1" >&2
  if [[ -n "${2:-}" ]]; then
    printf '%s\n' "$2" | sed 's/^/          /' >&2
  fi
  failures=$((failures + 1))
}

pass() {
  printf '  ok    %s\n' "$1"
}

assert_eq() {
  local description="$1" expected="$2" actual="$3"
  if [[ "$expected" == "$actual" ]]; then
    pass "$description"
  else
    fail "$description" "expected: $expected
  actual: $actual"
  fi
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------------------------
# The resolver
# ---------------------------------------------------------------------------------------------

# resolve <range> <version>...
#
# The version Helm's solver selects from the set, or `(no match)`. `helm search repo` reads its index
# from the repository cache, so a temp-dir HELM_REPOSITORY_CONFIG and HELM_REPOSITORY_CACHE hand Helm
# an arbitrary set; the URL is unreachable on purpose.
resolve() {
  local range="$1" version out
  shift

  local repo="$WORK/repo"
  rm -rf "$repo"
  mkdir -p "$repo/cache"

  printf 'apiVersion: ""\ngenerated: "0001-01-01T00:00:00Z"\nrepositories:\n- name: fixture\n  url: http://fixture.invalid\n' \
    >"$repo/repositories.yaml"

  {
    printf 'apiVersion: v1\nentries:\n  event-junkie:\n'
    for version in "$@"; do
      printf '  - name: event-junkie\n    version: %s\n    appVersion: %s\n    created: "2020-01-01T00:00:00Z"\n    digest: "0"\n    urls: ["http://fixture.invalid/event-junkie-%s.tgz"]\n' \
        "$version" "$version" "$version"
    done
  } >"$repo/cache/fixture-index.yaml"

  # `helm search repo` exits non-zero when nothing matches — a result worth naming in a failure
  # message, because it is what a missing `-0` looks like.
  out="$(
    HELM_REPOSITORY_CONFIG="$repo/repositories.yaml" HELM_REPOSITORY_CACHE="$repo/cache" \
      helm search repo fixture/event-junkie --version "$range" -o json 2>/dev/null || true
  )"

  if [[ -z "$out" || "$out" == "[]" ]]; then
    printf '(no match)\n'
    return
  fi
  printf '%s\n' "$out" | yq -p json -N '.[0].version'
}

# assert_resolves_newest <description> <range> <version-in-publication-order>...
#
# The versions are given oldest-published first, and the range must select the last one — the only
# thing "staging follows main" can mean.
assert_resolves_newest() {
  local description="$1" range="$2"
  shift 2
  local newest="${*: -1}"
  assert_eq "$description" "$newest" "$(resolve "$range" "$@")"
}

# ---------------------------------------------------------------------------------------------
# Fixtures
# ---------------------------------------------------------------------------------------------

# The ten snapshots published under the old scheme, in ASCII order, which is unrelated to publication
# order. Real shas: they are still in the registry and every new snapshot has to outrank them.
LEGACY_ASCII_ORDER=(
  0.1.0-snapshot.g21cfd20
  0.1.0-snapshot.g69bea59
  0.1.0-snapshot.g8955528
  0.1.0-snapshot.g9c64e89
  0.1.0-snapshot.gaeb1985
  0.1.0-snapshot.gc14ccfb
  0.1.0-snapshot.gcc52b77
  0.1.0-snapshot.gdf18a02
  0.1.0-snapshot.gf3c1146
  0.1.0-snapshot.gf6407e3
)

# The same ten shas published in reverse and half a minute apart, the worst case for the old scheme.
# Built with `version.sh compute` so this fixture tracks the scheme: revert version.sh and these fail.
SHAS_NEWEST_LAST=(f6407e3 f3c1146 df18a02 cc52b77 c14ccfb aeb1985 9c64e89 8955528 69bea59 21cfd20)
CURRENT_SCHEME=()
stamp=20260814120000
for sha in "${SHAS_NEWEST_LAST[@]}"; do
  CURRENT_SCHEME+=("$("$VERSION_SH" compute refs/heads/main "$sha" "$stamp")")
  stamp=$((stamp + 30))
done

# ---------------------------------------------------------------------------------------------
# Ordering — the regression this file is named for
# ---------------------------------------------------------------------------------------------

printf 'Ordering under staging'"'"'s range (%s)\n' "$STAGING_RANGE"

assert_resolves_newest \
  "ten snapshots published in order resolve to the tenth" \
  "$STAGING_RANGE" "${CURRENT_SCHEME[@]}"

# The failure exactly as it happened: with only the old tags, the range picks the sixth-oldest.
# Asserted, so "the old scheme was broken" is a checked claim.
assert_eq \
  "the old scheme resolved by ASCII, not by recency (the #455 bug, pinned)" \
  "0.1.0-snapshot.gf6407e3" \
  "$(resolve "$STAGING_RANGE" "${LEGACY_ASCII_ORDER[@]}")"

# Why the base version moved to 0.1.1: a numeric prerelease identifier ranks BELOW an alphanumeric
# one, so a timestamped snapshot of 0.1.0 would sort under all ten legacy tags.
assert_resolves_newest \
  "a new snapshot outranks every legacy tag still in the registry" \
  "$STAGING_RANGE" "${LEGACY_ASCII_ORDER[@]}" "${CURRENT_SCHEME[@]}"

assert_eq \
  "0.1.0-snapshot.<timestamp> would NOT have (why the base version had to move)" \
  "0.1.0-snapshot.gf6407e3" \
  "$(resolve "$STAGING_RANGE" "${LEGACY_ASCII_ORDER[@]}" "0.1.0-snapshot.20260814120000.g21cfd20")"

# The `-0` ADR-016 calls the entire mechanism: without it the same set matches nothing, silently.
assert_eq \
  "dropping the -0 from the range matches no snapshot at all" \
  "(no match)" \
  "$(resolve ">=0.0.0" "${CURRENT_SCHEME[@]}")"

printf '\nOrdering under production'"'"'s range (%s)\n' "$PRODUCTION_RANGE"

# Production excludes snapshots by omission, which is why its OCIRepository also carries a
# `semverFilter` — a Flux concept applied before the range, so it cannot be exercised here.
assert_eq \
  "no snapshot can satisfy production's range, whatever it sorts like" \
  "0.1.0" \
  "$(resolve "$PRODUCTION_RANGE" "${CURRENT_SCHEME[@]}" "0.1.0")"

# ---------------------------------------------------------------------------------------------
# The computed string itself
# ---------------------------------------------------------------------------------------------

printf '\nWhat version.sh compute produces\n'

base="$("$VERSION_SH" base)"

assert_eq \
  "a snapshot is a prerelease of the coming release, timestamp first" \
  "$base-snapshot.20260814122042.gdf18a02" \
  "$("$VERSION_SH" compute refs/heads/main df18a02cafe 20260814122042)"

# A pure function of the commit, so the timestamp is read from the commit, not the clock: a re-run of
# release.yml on the same sha must produce the same version.
assert_eq \
  "compute is deterministic for a given commit" \
  "$("$VERSION_SH" compute refs/heads/main "$(git -C "$REPO_ROOT" rev-parse HEAD)")" \
  "$("$VERSION_SH" compute refs/heads/main "$(git -C "$REPO_ROOT" rev-parse HEAD)")"

# ---------------------------------------------------------------------------------------------
# Still a version Helm will accept
# ---------------------------------------------------------------------------------------------

printf '\nChart acceptance\n'

# `release.yml` stamps the computed version over Chart.yaml, so the thing to lint is a stamped chart.
# The sha has a leading zero on purpose: the one commit in 270 where dropping the `g` prefix produces
# a version Helm rejects.
lint_stamped() {
  local version="$1" chart="$WORK/chart"
  rm -rf "$chart"
  # `-L`: `files/perf/` holds relative symlinks into `perf/` (#1697), which dangle in a copy placed
  # elsewhere, and a dangling symlink fails `helm lint` for a reason that has nothing to do with the version.
  cp -RL "$CHART_DIR" "$chart"
  VERSION="$version" yq -i '.version = strenv(VERSION) | .appVersion = strenv(VERSION)' "$chart/Chart.yaml"
  helm lint --strict "$chart" "${BASE_OVERRIDES[@]}" >/dev/null 2>&1
}

leading_zero="$("$VERSION_SH" compute refs/heads/main 0031234cafe 20260814122042)"
if lint_stamped "$leading_zero"; then
  pass "helm lint --strict accepts $leading_zero"
else
  fail "helm lint --strict rejected $leading_zero"
fi

# The same version with the `g` dropped: the prefix is load-bearing.
if lint_stamped "${leading_zero/.g/.}"; then
  fail "helm lint --strict accepted ${leading_zero/.g/.}, so the g prefix is no longer needed — check why"
else
  pass "helm lint --strict rejects it without the g prefix (leading zero in a numeric identifier)"
fi

# ---------------------------------------------------------------------------------------------

if ((failures != 0)); then
  printf '\n%s\n%s\n%s\n' \
    "$failures assertion(s) failed." \
    'These are ordering assertions, not format ones. A snapshot version that parses, lints and' \
    'publishes can still leave staging pinned to a chart from last week — that is #455.' >&2
  exit 1
fi

printf '\nAll assertions passed.\n'
