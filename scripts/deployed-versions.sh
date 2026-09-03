#!/usr/bin/env bash
#
# deployed-versions.sh — which published chart version each cluster would resolve, right now.
#
# It answers "what is running", deliberately not "what does `main` build" — those diverge the moment a
# base image is rebuilt. Nothing here reaches a cluster: under pull-based delivery (ADR-016) a cluster
# holds no inbound endpoint and CI holds no cluster credential, so the honest way to ask is to make
# the same selection Flux makes, from the same inputs.
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
# **The range comes out of `deploy/clusters/*/oci-repository.yaml`**, not from a constant here. That
# is the whole point: a scan that hardcoded ">=0.0.0-0" would keep passing while somebody narrowed
# staging's range, and would silently be measuring a version nothing runs. Read the file the cluster
# reads and the two cannot drift.
#
# **The tag list comes from the registry over the anonymous Docker v2 API.** The GHCR packages are
# public — as `oci-repository.yaml` already relies on — so this needs no credential, on CI or a laptop.
#
# **The selection is made by Helm's own solver**, through a fabricated repository index, exactly as
# `scripts/version-test.sh` does. Flux's source-controller and Helm share the Masterminds constraint
# implementation, so this reproduces the selection rather than approximating it. Reimplementing SemVer
# prerelease ordering in bash is the mistake #455 was: `0.1.0-snapshot.g<sha>` looked ordered and was
# not, and ten published charts resolved to the sixth-oldest.
#
# `semverFilter` is applied first, as a plain regex, because it is a Flux concept that Helm knows
# nothing about — production uses it to state "release versions only" positively rather than relying
# on Masterminds' prerelease omission. Skipping it here would report production as running a snapshot
# it would never pull.

set -euo pipefail

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
# Every tag published for an OCI repository, one per line.
#
# The token endpoint is not optional even for a public package: GHCR's v2 API answers 401 to an
# unauthenticated request and hands out a pull-scoped anonymous token for the asking. `--fail` on
# every call so a registry outage is an error rather than an empty tag list — an empty list would
# otherwise read as "nothing is published", which is the failure mode where a scan goes green having
# looked at nothing.
#
# **The tag list is paginated, and one page is not the answer.** GHCR caps a page at 100 tags and
# hands back the rest through a `Link: <…>; rel="next"` header. Reading only the first page is the
# same class of mistake as #455 and fails the same silent way: the newest tag on page one looks like
# the newest tag, so the caller resolves a real, published, months-old chart and reports it without
# any sign of truncation. When #1027 found it, 369 tags were published and 100 were read: the nightly
# image scan had been scanning a fortnight-old snapshot, and production — whose releases all sat past
# page one — was reported as having nothing to scan at all.
#
# `n=100` on the *first* request is load-bearing beyond page size: GHCR echoes the parameter into
# every `rel="next"` link it builds, so a first request without it yields a chain carrying `n=0`.
list_tags() {
  local registry="$1" repository="$2" token url next body page=0

  token="$(
    curl -fsSL "https://${registry}/token?scope=repository:${repository}:pull&service=${registry}" |
      yq -p json -N '.token'
  )" || die "could not get an anonymous pull token for ${registry}/${repository}"

  url="https://${registry}/v2/${repository}/tags/list?n=100"

  while [[ -n "$url" ]]; do
    page=$((page + 1))
    # A registry that keeps offering a next page forever would otherwise hang the caller. 50 pages
    # is 5000 tags — far past anything this project publishes, and still a bound.
    ((page <= 50)) || die "${registry}/${repository}: more than 50 pages of tags — refusing to loop"

    body="$(
      curl -fsSL -D "$WORK/tags-headers" -H "Authorization: Bearer ${token}" "$url"
    )" || die "could not list tags for ${registry}/${repository} (page ${page})"

    printf '%s' "$body" | yq -p json -N '.tags // [] | .[]'

    # Case-insensitive because the header name is, and `tr -d` because the value arrives CRLF
    # terminated. GHCR returns a path rather than an absolute URL; the spec permits either.
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
# The version Helm's constraint solver selects from the given set, or empty.
#
# `helm search repo` reads its index straight out of the repository cache, so pointing
# HELM_REPOSITORY_CONFIG and HELM_REPOSITORY_CACHE at a temp dir hands Helm an arbitrary set of
# versions. The URL on the entry is unreachable on purpose — nothing is ever downloaded, and a
# resolvable one would mean this could succeed or fail for network reasons.
#
# Lifted from `scripts/version-test.sh`, which asserts this technique against known-ordered
# publications. Kept as a copy rather than sourced: that file is a test with its own `main`, and
# making it a library would put an assertion harness on this script's path for no gain.
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

# The tag list is fetched once per chart URL and reused. All three clusters point at the same chart
# today, and asking the registry three times for the same answer is the kind of thing that quietly
# becomes a rate limit later.
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
    # `|| true` because grep exits 1 on no match, which is a result here — reported below as
    # "resolves nothing", with the range and filter named, rather than as a bare exit code.
    tags="$(printf '%s\n' "$tags" | grep -E "$filter" || true)"
  fi

  version=""
  if [[ -n "$tags" ]]; then
    # shellcheck disable=SC2086
    # Word splitting is intended: `resolve` takes one version per argument.
    version="$(resolve "$range" $tags)"
  fi

  # `(none)` rather than an error, and the distinction is the whole reason this branch is commented.
  # A range that admits nothing published is a result: failing here would paint the caller red every
  # night for a correct state, which is how a check gets switched off.
  #
  # It said so with production as the example until #1027 — "release versions only, and there has
  # never been a release". That was true when it was written and had stopped being true by `0.3.0`,
  # but the truncated tag list kept producing the output the sentence predicted, so the comment went
  # on explaining a wrong answer as a right one. **A branch this rarely taken is worth re-deriving
  # rather than reading.** No cluster resolves nothing today.
  #
  # A registry that will not answer, a chart URL with no tags at all, or a malformed source file are
  # different — those die above, loudly, because each of them is the shape of a scan that looks at
  # nothing while reporting success.
  if [[ -z "$version" ]]; then
    printf 'deployed-versions.sh: %s resolves nothing — semver %s, filter %s\n' \
      "$cluster" "$range" "${filter:-<none>}" >&2
    printf '%s\t(none)\n' "$cluster"
    continue
  fi

  printf '%s\t%s\n' "$cluster" "$version"
done

[[ "$found" -eq 1 ]] || die "no cluster named '${wanted}' under $CLUSTERS_DIR"
