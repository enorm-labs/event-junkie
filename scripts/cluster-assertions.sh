#!/usr/bin/env bash
#
# cluster-assertions.sh — the half of the deploy gate that a chart test suite structurally cannot be.
#
# `deploy/charts/event-junkie/tests/` holds the assertions about the *rendered chart*; this is what
# `helm unittest` structurally cannot see (#430), because it only ever sees the chart. Two jobs:
#
#   1. **Run the chart's invariant suites against each cluster's values.** Per-environment
#      configuration lives in each cluster's HelmRelease (#414), which is not a values document, so
#      `spec.values` is extracted and handed to `helm unittest --values`.
#
#   2. **Assert on relationships between files**: no published HelmRelease pins an image tag, the
#      ClusterIssuer stays out of the release and in a Kustomization that does not wait, a failing
#      Kustomization reaches a red workflow run, every third-party chart is pinned to one version, a
#      release creating its own namespace has that namespace declared with a Pod Security Admission level.
#
# Usage: scripts/cluster-assertions.sh [chart-dir] [clusters-dir]
#
# Requires: helm with the helm-unittest plugin, and yq. Reaches no cluster.

set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART_DIR="${1:-$REPO_ROOT/deploy/charts/event-junkie}"
CLUSTERS_DIR="${2:-$REPO_ROOT/deploy/clusters}"

# The suites that hold under any values file. Every assertion in them is independent of which host,
# port or database name an environment uses — a property to preserve when adding one.
INVARIANT_SUITES=(
    'tests/invariants_test.yaml'
    'tests/hardening_test.yaml'
    'tests/ingress_test.yaml'
    'tests/importer_test.yaml'
    'tests/bff_test.yaml'
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

# Whether a yq query over a file yields this exact line. It reads all the output before it matches:
# `grep -q` exits at the match, which can kill yq under pipefail, and a correct file then reads as wrong.
yq_lists() {
    local query="$1" file="$2" wanted="$3" output
    output="$(yq -N "$query" "$file")" || return 1
    grep -qxF -- "$wanted" <<<"$output"
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

        # An explicit path with its own XXXXXX: BSD mktemp treats the argument as a prefix, GNU requires the
        # X's. This form works on both.
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
# Every component's `image.tag` falls back to `.Chart.AppVersion`, the mechanism keeping chart and
# images in step (#264). An explicit tag opts that component out silently — the render looks correct
# while one workload is pinned to a version nobody chose. `values.yaml` is asserted by
# `invariants_test.yaml`; `values-k3d.yaml` pins `dev` and never leaves a laptop; every HelmRelease
# is checked, because those deploy.
check_image_tags() {
    printf '\n== image tags in published values ==\n'

    local file component
    for file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
        [[ -e "$file" ]] || continue
        current_case="$(basename "$(dirname "$file")")"
        for component in bff importer frontend frontend.injector; do
            assert_equals "helm-release.yaml: $component.image.tag is empty, so it falls back to appVersion" \
                "" "$(yq -N ".spec.values.${component}.image.tag // \"\"" "$file")"
            # Same trap one field over: release.yml stamps the digest (#1473).
            assert_equals "helm-release.yaml: $component.image.digest is empty, so release.yml's stamp holds" \
                "" "$(yq -N ".spec.values.${component}.image.digest // \"\"" "$file")"
        done
    done
}

# --- 2a-bis. A real database host needs a real database CIDR ------------------------------------
#
# `networkPolicy.databaseCidr` and `database.host` are two settings for one address, because
# NetworkPolicy cannot resolve a name (#416), and exactly one combination is silently wrong: a **real
# host with a placeholder CIDR**. `REPLACE-ME-…/32` templates, lints and passes every render, then the
# API server rejects it as an invalid `ipBlock.cidr` and the release rolls back several steps from
# anything mentioning a CIDR. Both placeholders (unprovisioned) and both real are fine, so the rule
# is the pairing, not the shape.
check_database_cidr() {
    printf '\n== database CIDR matches the database host ==\n'

    local file host cidr
    for file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
        [[ -e "$file" ]] || continue
        current_case="$(basename "$(dirname "$file")")"
        host="$(yq -N '.spec.values.database.host // ""' "$file")"
        cidr="$(yq -N '.spec.values.networkPolicy.databaseCidr // ""' "$file")"

        # Only a dotted-quad host can be checked against a CIDR: an unprovisioned environment carries
        # `REPLACE-ME` in both, and k3d reaches the host by NAME (`host.k3d.internal`) and widens the rule to
        # `0.0.0.0/0`. Neither can be silently wrong.
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

# --- 2b. The ClusterIssuer gates no deploy --------------------------------------------------------
#
# A release waits for everything it renders, so an issuer inside it turns a Let's Encrypt outage into
# a failed upgrade and a failed rollback (#4445). Every part fails quietly: nothing shows until the
# next outage, or until a certificate names an issuer that does not exist.
check_cluster_issuers() {
    printf '\n== cluster issuers ==\n'

    current_case="chart"
    if grep -rlE '^kind: *ClusterIssuer' "$CHART_DIR/templates" >/dev/null; then
        fail "the chart renders a ClusterIssuer" \
            "the release waits for it, so an ACME outage fails every upgrade and rollback; use issuer/"
    else
        pass "the chart renders no ClusterIssuer"
    fi

    local file dir cluster name issuer kustomization
    for file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
        [[ -e "$file" ]] || continue
        dir="$(dirname "$file")"
        cluster="$(basename "$dir")"
        current_case="$cluster"

        # No `//` here: yq reads `false // x` as x, and production inherits the chart's default.
        [[ "$(yq -N '.spec.values.ingress.tls.enabled' "$file")" != "false" ]] || continue

        name="$(yq -N '.spec.values.certManager.clusterIssuer.name // ""' "$file")"
        issuer="$dir/issuer/cluster-issuer.yaml"
        kustomization="$dir/issuer-kustomization.yaml"
        if [[ -e "$issuer" && "$(yq -N '.metadata.name' "$issuer")" == "$name" ]]; then
            pass "the Ingress names the issuer that issuer/ creates ($name)"
        else
            fail "the Ingress names '$name', which issuer/cluster-issuer.yaml does not create" \
                "cert-manager then waits for that issuer, and no certificate is issued"
        fi

        if [[ -e "$kustomization" &&
            "$(yq -N '.spec.wait // false' "$kustomization")" == "false" &&
            "$(yq -N '.spec.healthChecks // [] | length' "$kustomization")" == "0" &&
            "$(yq -N '.spec.path' "$kustomization")" == "./deploy/clusters/$cluster/issuer" ]]; then
            pass "issuer-kustomization.yaml applies issuer/ and does not wait"
        else
            fail "issuer-kustomization.yaml waits on the issuer, or applies another path" \
                "a wait makes an ACME outage fail this object, and it needs the path of its own cluster"
        fi

        if yq_lists '.resources[]' "$dir/kustomization.yaml" 'issuer-kustomization.yaml' &&
            yq_lists '.resources[]' "$dir/issuer/kustomization.yaml" 'cluster-issuer.yaml'; then
            pass "both kustomization.yaml files list the issuer's files"
        else
            fail "a kustomization.yaml does not list the issuer's files" \
                "a file not named in resources never reaches the cluster, and it still renders and lints"
        fi
    done
}

# --- 2b-i. A Kustomization that fails is heard ------------------------------------------------------
#
# The controller reports a failed apply only as an event (#4491). An object missing from the Alert
# sends none, and one missing from the workflow's `types` dispatches into nothing on GitHub. Both look
# exactly like a healthy cluster.
check_kustomization_alerts() {
    printf '\n== failing Kustomizations reach a workflow ==\n'

    local workflow="$REPO_ROOT/.github/workflows/flux-source-failure.yml"
    local dir cluster notification name found
    for notification in "$CLUSTERS_DIR"/*/notification.yaml; do
        [[ -e "$notification" ]] || continue
        dir="$(dirname "$notification")"
        cluster="$(basename "$dir")"
        current_case="$cluster"

        found=0
        # shellcheck disable=SC2016 # yq's own expression, not shell.
        while IFS= read -r name; do
            [[ -n "$name" ]] || continue
            found=1
            if NAME="$name" yq -N -e 'select(.kind == "Alert" and .metadata.name == "apply-failure") | .spec.eventSources[] | select(.kind == "Kustomization" and .name == strenv(NAME))' "$notification" >/dev/null 2>&1; then
                pass "the apply-failure Alert names $name"
            else
                fail "the apply-failure Alert does not name $name" \
                    "the cluster sends no event when it fails, and nothing else reports a failed apply"
            fi
            if yq_lists '.on.repository_dispatch.types[]' "$workflow" "Kustomization/$name.flux-system"; then
                pass "flux-source-failure.yml listens for $name"
            else
                fail "flux-source-failure.yml does not list Kustomization/$name.flux-system" \
                    "an unmatched dispatch leaves no trace on GitHub"
            fi
        done < <(find "$dir" -name '*.yaml' -print0 |
            xargs -0 yq -N 'select(.kind == "Kustomization" and (.apiVersion | test("toolkit.fluxcd.io"))) | .metadata.name' | grep -v '^---$' | sort -u)

        # An empty list would pass every line above.
        if ((found == 1)); then
            pass "found this cluster's Kustomizations"
        else
            fail "found no Flux Kustomization under $dir" "this check then proves nothing"
        fi
    done
}

# --- 2b-ii. Exactly one environment may be indexable --------------------------------------------
#
# `ingress.noindex` defaults to `false`, so *forgetting* it makes an environment indexable — an
# omission no single render can tell from a choice; only the set of clusters shows it. Production is
# the pair: while the domain is dark it serves a rehearsal hostname and MUST carry noindex; once
# `publish_dns` publishes the apex it must not. Going live is two edits in two repositories, and this
# ties them: the apex indexed with noindex on is an invisible launch, a rehearsal host without it is
# an unfinished site in Google. The canonical host comes from the chart's default.
check_noindex() {
    printf '\n== only production is indexable ==\n'

    local file cluster noindex
    for file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
        [[ -e "$file" ]] || continue
        cluster="$(basename "$(dirname "$file")")"
        current_case="$cluster"

        noindex="$(yq -N '.spec.values.ingress.noindex // false' "$file")"
        if [[ "$cluster" == "production" ]]; then
            local canonical host
            canonical="$(yq -N '.ingress.host' "$CHART_DIR/values.yaml")"
            host="$(yq -N ".spec.values.ingress.host // \"$canonical\"" "$file")"

            if [[ "$host" == "$canonical" ]]; then
                assert_equals "serves the canonical host ($canonical), so it is indexable" "false" "$noindex"
            else
                assert_equals "serves the rehearsal host ($host) while dark, so it is not indexable" "true" "$noindex"
            fi
        else
            if [[ "$noindex" == "true" ]]; then
                pass "not production, and not indexable"
            else
                fail "a non-production cluster does not set ingress.noindex" \
                    "the default is false, so this is indexable: no X-Robots-Tag, the image's robots.txt open to
every crawler but the AI training ones, and a sitemap naming production.
Set ingress.noindex: true in spec.values."
            fi
        fi
    done
}

# --- 2c. Every third-party chart is pinned to one version ---------------------------------------
#
# A range lets an upstream release reach the cluster with no diff, no review and no commit — the
# property GitOps exists to remove. **Iterate documents, not files**, here and in every check that
# walks `*.yaml`: `yq -N '.kind' "$file"` prints one line per document, so a file holding a
# HelmRepository and a HelmRelease matched nothing, and this once covered three releases out of nine
# while reporting green — the four most recently added were the four nobody was checking.
check_version_pins() {
    printf '\n== third-party chart versions ==\n'

    local release name version
    for release in "$CLUSTERS_DIR"/*/*.yaml; do
        [[ -e "$release" ]] || continue
        while IFS=$'\t' read -r name version; do
            # An OCIRepository-backed release carries `chartRef` and no `chart.spec.version`; its version lives in
            # the OCIRepository.
            [[ -n "$version" ]] || continue

            current_case="$(basename "$(dirname "$release")")/$(basename "$release"):$name"
            if [[ "$version" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
                pass "chart version is pinned exactly ($version)"
            else
                fail "chart version '$version' is a range, not a pin" \
                    "an upstream release could then reach the cluster with no commit and no review"
            fi
        done < <(yq -N 'select(.kind=="HelmRelease") | (.metadata.name // "?") + "\t" + (.spec.chart.spec.version // "")' "$release")
    done
}

# --- 2d. Every namespace a release creates is declared, and carries a PSA level -----------------
#
# **`createNamespace: true` creates a bare namespace**, and Pod Security Admission is enforced by
# namespace *label*, so a release that makes its own namespace makes an ungoverned one with no visible
# symptom. #604 found the label on one namespace out of eight, `observability` among the seven. The
# failure this prevents is the *next* HelmRelease with `createNamespace: true` and no Namespace
# manifest, in a diff that looks routine. It asserts a level is declared, not *which*:
# `observability-agent` is deliberately `privileged` (#709). Declaring is the reviewable act.
check_namespace_governance() {
    printf '\n== namespaces are declared, not conjured by createNamespace ==\n'

    local release cluster_dir name target declared
    local -a search
    for release in "$CLUSTERS_DIR"/*/*.yaml; do
        [[ -e "$release" ]] || continue
        cluster_dir="$(dirname "$release")"

        # `base/` holds the namespaces both clusters apply (#953), searched only when the cluster's
        # `kustomization.yaml` names `../base` — a Namespace manifest no `resources:` list names never
        # reaches the cluster.
        search=("$cluster_dir"/*.yaml)
        if grep -qE '^[[:space:]]*-[[:space:]]*\.\./base[[:space:]]*$' "$cluster_dir/kustomization.yaml" 2>/dev/null; then
            search+=("$CLUSTERS_DIR"/base/*.yaml)
        fi

        # Per document, for the reason check_version_pins records.
        while IFS=$'\t' read -r name target; do
            [[ -n "$target" ]] || continue
            current_case="$(basename "$cluster_dir")/$(basename "$release"):$name"

            # Every Namespace this cluster applies with an enforce label, recomputed per release; a stale cache
            # here passes on a file someone deleted.
            declared="$(yq -N 'select(.kind=="Namespace" and .metadata.labels["pod-security.kubernetes.io/enforce"] != null) | .metadata.name' \
                "${search[@]}" 2>/dev/null || true)"

            if grep -qxF "$target" <<<"$declared"; then
                pass "namespace '$target' is declared with a Pod Security Admission level"
            else
                fail "namespace '$target' is created by this release but declared nowhere" \
                    "createNamespace makes a bare namespace, so PSA has no label to enforce and every pod is admitted;
add a Namespace manifest carrying pod-security.kubernetes.io/enforce (#604)"
            fi
        done < <(yq -N 'select(.kind=="HelmRelease" and .spec.install.createNamespace == true) | (.metadata.name // "?") + "\t" + (.spec.targetNamespace // "")' "$release")
    done
}

main() {
    command -v helm >/dev/null || {
        echo "helm is not installed" >&2
        exit 127
    }
    command -v yq >/dev/null || {
        echo "yq is not installed" >&2
        exit 127
    }
    local plugins
    plugins="$(helm plugin list 2>/dev/null || true)"
    grep -q '^unittest' <<<"$plugins" || {
        echo "the helm-unittest plugin is not installed — see deploy/AGENTS.md" >&2
        exit 127
    }

    check_image_tags
    check_database_cidr
    check_cluster_issuers
    check_kustomization_alerts
    check_noindex
    check_version_pins
    check_namespace_governance
    run_suites_against_clusters

    printf '\n'
    if ((failures > 0)); then
        printf '%d assertion(s) failed\n' "$failures" >&2
        exit 1
    fi
    printf 'all assertions passed\n'
}

main "$@"
