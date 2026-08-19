#!/usr/bin/env bash
#
# cluster-assertions.sh — the half of the deploy gate that a chart test suite structurally cannot be.
#
# `deploy/charts/event-junkie/tests/` holds the assertions about the *rendered chart*, and
# `helm unittest` runs them. This script is what is left over after that port (#430), and it is left
# over for one reason: helm-unittest only ever sees the chart. Everything here is about
# `deploy/clusters/`, which is not part of the chart and never will be.
#
# Two jobs:
#
#   1. **Run the chart's own invariant suites against each cluster's values.** Since #414 the
#      per-environment configuration lives in each cluster's HelmRelease rather than in a values
#      file, because a HelmRelease cannot read one from the repository and two copies would drift.
#      A HelmRelease is not a values document, so `spec.values` is extracted and handed to
#      `helm unittest --values`. Without this the suites would cover the two configurations that
#      deploy nowhere and none of the three that deploy.
#
#   2. **Assert on relationships between files**, which no single render can see: that no published
#      HelmRelease pins an image tag, that a release creating a ClusterIssuer declares `dependsOn`,
#      and that every third-party chart is pinned to one version rather than a range.
#
# Usage: scripts/cluster-assertions.sh [chart-dir] [clusters-dir]
#
# Requires: helm with the helm-unittest plugin, and yq. Reaches no cluster and needs no kubeconfig —
# `helm unittest` renders, which is a pure function of the working tree.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART_DIR="${1:-$REPO_ROOT/deploy/charts/event-junkie}"
CLUSTERS_DIR="${2:-$REPO_ROOT/deploy/clusters}"

# The suites that hold under any values file, and therefore the ones worth re-running per cluster.
# Every assertion in them is phrased so that it does not depend on which host, port or database name
# an environment happens to use — that is a property to preserve when adding one, not an accident.
INVARIANT_SUITES=(
  'tests/invariants_test.yaml'
  'tests/hardening_test.yaml'
  'tests/ingress_test.yaml'
  'tests/importer_test.yaml'
  'tests/seo_test.yaml'
  'tests/probes_test.yaml'
  'tests/network_test.yaml'
)

failures=0
current_case=""

fail() {
  printf '  FAIL  [%s] %s\n' "$current_case" "$1" >&2
  if [[ -n "${2:-}" ]]; then
    printf '%s\n' "$2" | sed 's/^/          /' >&2
  fi
  failures=$((failures + 1))
}

pass() {
  printf '  ok    [%s] %s\n' "$current_case" "$1"
}

assert_equals() {
  local description="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    pass "$description"
  else
    fail "$description" "expected '$expected', got '$actual'"
  fi
}

# --- 1. The chart's invariants, against what Flux will actually apply ---------------------------

run_suites_against_clusters() {
  printf '\n== chart invariants, per cluster ==\n'

  local file cluster values suites=()
  for suite in "${INVARIANT_SUITES[@]}"; do
    suites+=(--file "$suite")
  done

  for file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
    [[ -e "$file" ]] || continue
    cluster="$(basename "$(dirname "$file")")"
    current_case="$cluster"

    # An explicit path with its own XXXXXX rather than `mktemp -t <prefix>`: BSD mktemp treats the
    # argument as a prefix and appends the random part, while GNU coreutils requires the X's to be
    # there already and fails with "too few X's in template". This form works on both.
    values="$(mktemp "${TMPDIR:-/tmp}/event-junkie-values.XXXXXX")"
    yq -N '.spec.values' "$file" >"$values"

    printf '\n-- %s --\n' "$cluster"
    if helm unittest --strict "${suites[@]}" --values "$values" "$CHART_DIR"; then
      pass "the chart's invariants hold under $cluster's spec.values"
    else
      fail "the chart's invariants do not hold under $cluster's spec.values"
    fi
    rm -f "$values"
  done
}

# --- 2a. Published values files must not pin an image tag ---------------------------------------
#
# Every component's `image.tag` defaults to "" and falls back to `.Chart.AppVersion`, and that
# fallback is the whole mechanism keeping the chart and the images in step (#264): one number
# stamped at build time reaches all four artifacts. An explicit tag in a published values file
# silently opts that component out — the render still looks entirely correct, with a plausible tag
# on every image, while one workload is pinned to a version nobody chose.
#
# The chart's own `values.yaml` is asserted by `tests/invariants_test.yaml`, which checks the
# rendered image rather than the file. `values-k3d.yaml` is deliberately exempt: it pins `dev` for
# locally built images, and it never leaves a laptop. Every HelmRelease is checked, because those
# are what deploy.
check_image_tags() {
  printf '\n== image tags in published values ==\n'

  local file component
  for file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
    [[ -e "$file" ]] || continue
    current_case="$(basename "$(dirname "$file")")"
    for component in bff importer frontend; do
      assert_equals "helm-release.yaml: $component.image.tag is empty, so it falls back to appVersion" \
        "" "$(yq -N ".spec.values.${component}.image.tag // \"\"" "$file")"
    done
  done
}

# --- 2a-bis. A real database host needs a real database CIDR ------------------------------------
#
# `networkPolicy.databaseCidr` and `database.host` are two settings for one address, because
# NetworkPolicy speaks CIDRs and cannot resolve a name (#416). That duplication is unavoidable and
# it has exactly one silently-wrong combination: a **real host with a placeholder CIDR**.
#
# It is silent because the value is a string. `REPLACE-ME-tofu-output-postgres-ip/32` templates
# perfectly, passes `helm lint`, passes every render assertion here — and is then rejected by the
# API server as an invalid `ipBlock.cidr`, which fails the HelmRelease and rolls the release back
# several steps from anything that mentions a CIDR. `required` cannot catch it: the value is
# present, it is just nonsense.
#
# The other two combinations are fine and must stay fine, which is why this is not a blanket format
# check: both placeholders is an environment nobody has provisioned yet, and both real is correct.
# So the rule is the pairing, not the shape.
check_database_cidr() {
  printf '\n== database CIDR matches the database host ==\n'

  local file host cidr
  for file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
    [[ -e "$file" ]] || continue
    current_case="$(basename "$(dirname "$file")")"
    host="$(yq -N '.spec.values.database.host // ""' "$file")"
    cidr="$(yq -N '.spec.values.networkPolicy.databaseCidr // ""' "$file")"

    # Only a dotted-quad host can be checked against a CIDR. Two other shapes are legitimate and
    # must stay so: an un-provisioned environment carries a `REPLACE-ME` placeholder in both, and
    # k3d reaches the host by NAME (`host.k3d.internal`, an address k3d assigns at cluster-create
    # time), which no values file can know — so it widens the rule to `0.0.0.0/0` and relies on the
    # port instead. Neither can be silently wrong; a real host with a placeholder CIDR can.
    if [[ "$host" =~ ^[0-9]+\.[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      assert_equals "helm-release.yaml: databaseCidr is the /32 of database.host ($host)" \
        "${host}/32" "$cidr"
    elif [[ -n "$cidr" ]]; then
      pass "database.host is '$host', not an address, so its CIDR ('$cidr') cannot be derived from it"
    else
      fail "helm-release.yaml: networkPolicy.databaseCidr is empty; the render will fail on it"
    fi
  done
}

# --- 2b. A ClusterIssuer needs cert-manager to already be there ---------------------------------
#
# The chart's ClusterIssuer template renders a `cert-manager.io/v1` object, and the API server
# rejects an unknown kind — so a cluster whose application HelmRelease sets
# `certManager.clusterIssuer.create: true` without a `dependsOn` installs nothing at all. Not the
# issuer: the whole release, workloads included.
#
# It fails on the very first bootstrap of a new cluster and looks like a chart bug, which is the
# expensive kind of failure. #265 added the dependency; this is what keeps it.
check_cluster_dependencies() {
  printf '\n== cluster dependencies ==\n'

  local file cluster creates depends
  for file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
    [[ -e "$file" ]] || continue
    cluster="$(basename "$(dirname "$file")")"
    current_case="$cluster"

    creates="$(yq -N '.spec.values.certManager.clusterIssuer.create // false' "$file")"
    [[ "$creates" == "true" ]] || continue

    depends="$(yq -N '.spec.dependsOn[].name // ""' "$file")"
    if [[ -n "$(printf '%s' "$depends" | tr -d '[:space:]')" ]]; then
      pass "creates a ClusterIssuer and declares dependsOn ($(printf '%s' "$depends" | tr '\n' ' '))"
    else
      fail "creates a ClusterIssuer but declares no dependsOn" \
        "the release renders a cert-manager.io/v1 kind; without cert-manager installed first the
whole release fails on an unknown kind, not just the issuer"
    fi
  done
}

# --- 2b-ii. Exactly one environment may be indexable --------------------------------------------
#
# `ingress.noindex` is per-cluster and its default is `false`, so *forgetting* it is what makes an
# environment indexable — the failure is an omission, which no render of that cluster alone can
# distinguish from a deliberate choice. Only the set of clusters shows it, which is why this is here
# and not in a suite.
#
# The direction that matters is the omission on a non-production cluster: production is loudly wrong
# the moment it is not in Google, while a staging environment that quietly is stays that way for
# months (#265, #286).
check_noindex() {
  printf '\n== only production is indexable ==\n'

  local file cluster noindex
  for file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
    [[ -e "$file" ]] || continue
    cluster="$(basename "$(dirname "$file")")"
    current_case="$cluster"

    noindex="$(yq -N '.spec.values.ingress.noindex // false' "$file")"
    if [[ "$cluster" == "production" ]]; then
      assert_equals "production is indexable, which is the whole point of it" "false" "$noindex"
    else
      if [[ "$noindex" == "true" ]]; then
        pass "not production, and not indexable"
      else
        fail "a non-production cluster does not set ingress.noindex" \
          "the default is false, so this is indexable: no X-Robots-Tag, an allow-all robots.txt,
and a sitemap naming production. Set ingress.noindex: true in spec.values."
      fi
    fi
  done
}

# --- 2c. Every third-party chart is pinned to one version ---------------------------------------
#
# A range lets a new upstream release reach the cluster with no diff, no review and no commit —
# which is the property GitOps exists to remove, and it would be silent.
check_version_pins() {
  printf '\n== third-party chart versions ==\n'

  local release version cluster
  for release in "$CLUSTERS_DIR"/*/*.yaml; do
    [[ -e "$release" ]] || continue
    [[ "$(yq -N '.kind // ""' "$release")" == "HelmRelease" ]] || continue
    version="$(yq -N '.spec.chart.spec.version // ""' "$release")"
    [[ -n "$version" ]] || continue

    cluster="$(basename "$(dirname "$release")")/$(basename "$release")"
    current_case="$cluster"
    if [[ "$version" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      pass "chart version is pinned exactly ($version)"
    else
      fail "chart version '$version' is a range, not a pin" \
        "an upstream release could then reach the cluster with no commit and no review"
    fi
  done
}

main() {
  command -v helm >/dev/null || { echo "helm is not installed" >&2; exit 127; }
  command -v yq >/dev/null || { echo "yq is not installed" >&2; exit 127; }
  helm plugin list 2>/dev/null | grep -q '^unittest' || {
    echo "the helm-unittest plugin is not installed — see deploy/AGENTS.md" >&2
    exit 127
  }

  check_image_tags
  check_database_cidr
  check_cluster_dependencies
  check_noindex
  check_version_pins
  run_suites_against_clusters

  printf '\n'
  if ((failures > 0)); then
    printf '%d assertion(s) failed\n' "$failures" >&2
    exit 1
  fi
  printf 'all assertions passed\n'
}

main "$@"
