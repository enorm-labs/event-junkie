#!/usr/bin/env bash
#
# deployed-versions.sh — which published chart version each cluster would resolve, right now.
#
# "What is running", not "what does `main` build" — the two diverge the moment a base image is
# rebuilt. Nothing here reaches a cluster (ADR-016): the honest way to ask is to make the selection
# Flux makes, from the same inputs.
#
# Usage:
#   scripts/deployed-versions.sh            # every cluster under deploy/clusters/
#   scripts/deployed-versions.sh staging    # just this one
#
# Output is one `cluster<TAB>version` line per cluster, in directory order. A cluster whose range
# matches nothing published prints `(none)` plus a line on stderr saying why:
#
#   k3d          0.3.9-snapshot.20260903121833.g75cadf2
#   production   0.3.8
#   staging      0.3.9-snapshot.20260903121833.g75cadf2
#
# Requires: curl, yq, helm. Reaches the registry, and writes only under a temp dir it removes.
#
# **The range comes out of `deploy/clusters/*/oci-repository.yaml`**, so a narrowed range cannot leave
# this measuring a version nothing runs. **The tag list comes from the anonymous Docker v2 API** —
# the packages are public. **The selection is made by Helm's own solver** through a fabricated
# repository index, as `scripts/version-test.sh` does; source-controller and Helm share the
# Masterminds constraint library, and reimplementing prerelease ordering in bash is the mistake #455
# was. `semverFilter` is applied first, as a plain regex: a Flux concept Helm knows nothing about,
# and production uses it to say "release versions only".

set -euo pipefail

case "${1:-}" in
  -h | --help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLUSTERS_DIR="$REPO_ROOT/deploy/clusters"

die() {
  printf 'deployed-versions.sh: %s\n' "$1" >&2
  exit 1
}

for tool in curl yq helm; do
  command -v "$tool" >/dev/null || die "$tool is required but not on PATH"
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# list_tags <registry> <repository>
#
# Every tag published for an OCI repository, one per line. The token endpoint is not optional even
# for a public package: GHCR answers 401 and hands out a pull-scoped anonymous token. `--fail` on every
# call, so a registry outage is an error rather than an empty list that reads as "nothing published".
#
# **The tag list is paginated, and one page is not the answer.** GHCR caps a page at 100 and hands
# back the rest through a `Link: <…>; rel="next"` header. Reading one page fails the way #455 did:
# the newest tag on page one looks newest, and #1027 found 369 tags published, 100 read, the nightly
# scan on a fortnight-old snapshot and production reported as unscannable. `n=100` on the *first*
# request is load-bearing beyond page size: GHCR echoes it into every `rel="next"` link, so a first
# request without it yields a chain carrying `n=0`.
list_tags() {
  local registry="$1" repository="$2" token url next body page=0

  token="$(
    curl -fsSL "https://${registry}/token?scope=repository:${repository}:pull&service=${registry}" |
      yq -p json -N '.token'
  )" || die "could not get an anonymous pull token for ${registry}/${repository}"

  url="https://${registry}/v2/${repository}/tags/list?n=100"

  while [[ -n "$url" ]]; do
    page=$((page + 1))
    # A bound, so a registry offering a next page forever cannot hang the caller.
    ((page <= 50)) || die "${registry}/${repository}: more than 50 pages of tags — refusing to loop"

    body="$(
      curl -fsSL -D "$WORK/tags-headers" -H "Authorization: Bearer ${token}" "$url"
    )" || die "could not list tags for ${registry}/${repository} (page ${page})"

    printf '%s' "$body" | yq -p json -N '.tags // [] | .[]'

    # Case-insensitive because the header name is; `tr -d` because the value arrives CRLF terminated.
    # GHCR returns a path rather than an absolute URL.
    next="$(
      sed -n 's/^[Ll]ink:[[:space:]]*<\([^>]*\)>;[[:space:]]*rel="next".*/\1/p' "$WORK/tags-headers" |
        tr -d '\r' | tail -1
    )"
    case "$next" in
      '') url="" ;;
      /*) url="https://${registry}${next}" ;;
      *) url="$next" ;;
    esac
  done
}

# resolve <range> <version>...
#
# The version Helm's constraint solver selects from the set, or empty. `helm search repo` reads its
# index from the repository cache, so pointing HELM_REPOSITORY_CONFIG and HELM_REPOSITORY_CACHE at a
# temp dir hands Helm an arbitrary set; the URL on the entry is unreachable on purpose. A copy of
# `scripts/version-test.sh`'s function rather than sourced: that file is a test with its own `main`.
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

  out="$(
    HELM_REPOSITORY_CONFIG="$repo/repositories.yaml" HELM_REPOSITORY_CACHE="$repo/cache" \
      helm search repo fixture/event-junkie --version "$range" -o json 2>/dev/null || true
  )"

  [[ -n "$out" && "$out" != "[]" ]] || return 0
  printf '%s\n' "$out" | yq -p json -N '.[0].version'
}

# ---------------------------------------------------------------------------------------------

wanted="${1:-}"
[[ -d "$CLUSTERS_DIR" ]] || die "no such directory: $CLUSTERS_DIR"

# Fetched once per chart URL; all three clusters point at the same chart, and asking three times is
# what becomes a rate limit later.
declare -A TAG_CACHE=()

found=0
for source_file in "$CLUSTERS_DIR"/*/oci-repository.yaml; do
  [[ -e "$source_file" ]] || die "no oci-repository.yaml under $CLUSTERS_DIR"

  cluster="$(basename "$(dirname "$source_file")")"
  [[ -z "$wanted" || "$cluster" == "$wanted" ]] || continue
  found=1

  url="$(yq -N '.spec.url' "$source_file")"
  range="$(yq -N '.spec.ref.semver' "$source_file")"
  filter="$(yq -N '.spec.ref.semverFilter // ""' "$source_file")"

  [[ "$url" == oci://* ]] || die "$cluster: spec.url is '$url', which is not an OCI reference"
  [[ -n "$range" && "$range" != "null" ]] ||
    die "$cluster: no spec.ref.semver — this script only understands the semver selector"

  reference="${url#oci://}"
  registry="${reference%%/*}"
  repository="${reference#*/}"

  if [[ -z "${TAG_CACHE[$reference]+set}" ]]; then
    TAG_CACHE[$reference]="$(list_tags "$registry" "$repository")"
  fi

  tags="${TAG_CACHE[$reference]}"
  [[ -n "$tags" ]] || die "$cluster: $reference has no published tags at all"

  if [[ -n "$filter" && "$filter" != "null" ]]; then
    # `|| true`: grep exits 1 on no match, which is a result reported below, not an error.
    tags="$(printf '%s\n' "$tags" | grep -E "$filter" || true)"
  fi

  version=""
  if [[ -n "$tags" ]]; then
    # shellcheck disable=SC2086
    # Word splitting is intended: `resolve` takes one version per argument.
    version="$(resolve "$range" $tags)"
  fi

  # `(none)` rather than an error: a range that admits nothing published is a result, and failing here
  # would paint the caller red every night for a correct state. This branch once explained production
  # as the example — "there has never been a release" — for months after that stopped being true,
  # because the truncated tag list kept producing the output the sentence predicted (#1027). **A branch
  # this rarely taken is worth re-deriving rather than reading.** A registry that will not answer, a
  # chart with no tags at all, or a malformed source file die above, loudly.
  if [[ -z "$version" ]]; then
    printf 'deployed-versions.sh: %s resolves nothing — semver %s, filter %s\n' \
      "$cluster" "$range" "${filter:-<none>}" >&2
    printf '%s\t(none)\n' "$cluster"
    continue
  fi

  printf '%s\t%s\n' "$cluster" "$version"
done

[[ "$found" -eq 1 ]] || die "no cluster named '${wanted}' under $CLUSTERS_DIR"
