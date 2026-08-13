#!/usr/bin/env bash
#
# render-assertions.sh — assert things about the *rendered* chart that lint cannot see.
#
# `helm lint` checks the chart is well-formed and `kubeconform` checks the output matches the
# Kubernetes schemas. Both pass on a chart that quietly does the wrong thing: an Ingress that routes
# /actuator, an importer scaled to two replicas, a Deployment whose immutable selector carries a
# label that changes on every release. This script is the only gate that catches those, so it runs
# in CI (.github/workflows/validate-chart.yml) and under /verify on any diff touching deploy/.
#
# Usage: deploy/scripts/render-assertions.sh [chart-dir]
#
# Renders the chart once per values file and runs every assertion against each result. Exits
# non-zero on the first failing assertion in any render, printing the offending output.
#
# Requires: helm, yq. Reaches no cluster and needs no kubeconfig — `helm template` is a pure
# function of the working tree, which is why this is safe to run constantly.

set -euo pipefail

CHART_DIR="${1:-deploy/charts/event-junkie}"
# Since #414 the per-environment configuration lives in each cluster's HelmRelease rather than in a
# values file, because a HelmRelease cannot read one from the repository and two copies would drift.
# So this script renders the chart with the values Flux will actually apply — which is the whole
# point: before, these assertions gated a file nothing deployed.
CLUSTERS_DIR="${2:-deploy/clusters}"

# The base values.yaml deliberately cannot render on its own: database.host and
# database.existingSecret have no safe default and the helpers `required` them. So the default case
# supplies the two, and the environment cases get theirs from their own values file.
BASE_OVERRIDES=(--set "database.host=10.0.1.2" --set "database.existingSecret=events-db")

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

# assert_empty <description> <output>
#
# The workhorse: every assertion here is phrased as a yq query that yields nothing when the chart is
# correct and yields the offending values when it is not. Phrasing them that way means a query that
# matches nothing because it was written wrong looks like a pass — so `assert_nonempty` exists too,
# and the queries that can silently match nothing are paired with one.
assert_empty() {
  local description="$1" output="$2"
  if [[ -z "$(printf '%s' "$output" | tr -d '[:space:]')" ]]; then
    pass "$description"
  else
    fail "$description" "$output"
  fi
}

assert_nonempty() {
  local description="$1" output="$2"
  if [[ -n "$(printf '%s' "$output" | tr -d '[:space:]')" ]]; then
    pass "$description"
  else
    fail "$description" "(query returned nothing — the assertion below it is not actually checking anything)"
  fi
}

# assert_equals <description> <expected> <actual>
assert_equals() {
  local description="$1" expected="$2" actual="$3"
  if [[ "$actual" == "$expected" ]]; then
    pass "$description"
  else
    fail "$description" "expected '$expected', got '$actual'"
  fi
}

run_assertions() {
  local manifest="$1"

  # --- The ingress must not reach anything private -------------------------------------------
  #
  # The importer's /api/admin/** and every /actuator/** endpoint are unreachable from outside the
  # cluster by construction: the importer has no Ingress backend at all, and actuator lives on its
  # own port that no Ingress rule names. Both properties are one careless path entry away from
  # being false, and neither failure is visible in a diff.

  assert_nonempty "the ingress routes something (else the three checks below are vacuous)" \
    "$(yq -N 'select(.kind == "Ingress") | .spec.rules[].http.paths[].path' "$manifest")"

  assert_empty "no ingress backend names the importer service" \
    "$(yq -N 'select(.kind == "Ingress") | .spec.rules[].http.paths[].backend.service.name' "$manifest" | grep -- '-importer$' || true)"

  assert_empty "no ingress path contains /actuator" \
    "$(yq -N 'select(.kind == "Ingress") | .spec.rules[].http.paths[].path' "$manifest" | grep -- 'actuator' || true)"

  assert_empty "no ingress backend targets the management port" \
    "$(yq -N 'select(.kind == "Ingress") | .spec.rules[].http.paths[].backend.service.port | [.name, .number] | .[]' "$manifest" |
      grep -Ex 'management|9001' || true)"

  # --- noindex is a two-part mechanism, and half of it is silent ------------------------------
  #
  # The header needs both a Middleware and an Ingress annotation naming it. Render only the
  # Middleware and every response is missing the header while the manifest looks entirely correct;
  # render only the annotation and Traefik drops the route with a middleware-not-found error. The
  # first failure mode is the dangerous one — a staging environment that believes it is noindexed.
  #
  # Asserted as an equality between the two halves rather than as their presence, because this must
  # hold in *both* directions: production renders neither, and that is also correct.

  assert_equals "the noindex middleware and the annotation naming it agree" \
    "$(yq -N 'select(.kind == "Middleware") | select(.metadata.name == "*-noindex") | .metadata.name' "$manifest" | wc -l | tr -d ' ')" \
    "$(yq -N 'select(.kind == "Ingress") | .metadata.annotations["traefik.ingress.kubernetes.io/router.middlewares"] // ""' "$manifest" | grep -c -- '-noindex@kubernetescrd' || true)"

  assert_empty "no noindex middleware sets an empty X-Robots-Tag" \
    "$(yq -N 'select(.kind == "Middleware") | .spec.headers.customResponseHeaders["X-Robots-Tag"] // "" | select(. == "")' "$manifest")"

  # --- The importer's single replica is an ADR-008 correctness constraint ---------------------
  #
  # Two schedulers means two concurrent imports of the same source; the 60-second tick's RUNNING
  # check is a read-then-write with no lock. A rolling update briefly runs two pods, which is the
  # same failure by a different route — hence Recreate.

  assert_equals "importer replicas is 1" "1" \
    "$(yq -N 'select(.kind == "Deployment") | select(.metadata.name == "*-importer") | .spec.replicas' "$manifest")"

  assert_equals "importer strategy is Recreate" "Recreate" \
    "$(yq -N 'select(.kind == "Deployment") | select(.metadata.name == "*-importer") | .spec.strategy.type' "$manifest")"

  # --- Flyway's JDBC connection ---------------------------------------------------------------
  #
  # The apps are R2DBC and Flyway is JDBC, so the importer needs two connection configurations for
  # one database. Forgetting the JDBC half fails silently: migrations simply never run.

  assert_nonempty "importer sets SPRING_FLYWAY_URL" \
    "$(yq -N 'select(.kind == "Deployment") | select(.metadata.name == "*-importer") | .spec.template.spec.containers[].env[] | select(.name == "SPRING_FLYWAY_URL") | .value' "$manifest")"

  assert_nonempty "importer sets SPRING_FLYWAY_USER (not _USERNAME, which binds to nothing)" \
    "$(yq -N 'select(.kind == "Deployment") | select(.metadata.name == "*-importer") | .spec.template.spec.containers[].env[] | select(.name == "SPRING_FLYWAY_USER") | .name' "$manifest")"

  assert_empty "nothing sets SPRING_FLYWAY_USERNAME" \
    "$(yq -N '.. | select(has("name")) | select(.name == "SPRING_FLYWAY_USERNAME") | .name' "$manifest")"

  # --- Selector labels must be the immutable subset -------------------------------------------
  #
  # spec.selector is immutable after creation, and helm.sh/chart and app.kubernetes.io/version both
  # change on every release. A chart that mixes them installs perfectly and then fails every
  # subsequent upgrade — a failure that does not appear until the *second* release, which is why
  # nothing but this assertion would catch it before staging.

  assert_nonempty "some workload declares a selector at all" \
    "$(yq -N 'select(.kind == "Deployment") | .spec.selector.matchLabels | keys | .[]' "$manifest")"

  assert_empty "no selector carries a label that changes between releases" \
    "$({
      yq -N 'select(.kind == "Deployment") | .spec.selector.matchLabels | keys | .[]' "$manifest"
      yq -N 'select(.kind == "Service") | .spec.selector | keys | .[]' "$manifest"
    } | grep -Ex 'helm.sh/chart|app.kubernetes.io/version' || true)"

  # --- Workload hardening — PLATFORM_SETUP §8 items 5, 6, 7 and 12 ----------------------------
  #
  # Requests and limits are a security control on a single node, not a tuning preference: one
  # workload without a memory limit can evict the other two.

  assert_nonempty "some pod declares runAsNonRoot" \
    "$(yq -N '.. | select(has("runAsNonRoot")) | .runAsNonRoot' "$manifest")"

  assert_empty "no pod sets runAsNonRoot false" \
    "$(yq -N '.. | select(has("runAsNonRoot")) | select(.runAsNonRoot == false) | .runAsNonRoot' "$manifest")"

  assert_empty "every pod template sets runAsNonRoot" \
    "$(yq -N 'select(.kind == "Deployment") | select(.spec.template.spec.securityContext.runAsNonRoot != true) | .metadata.name' "$manifest")"

  assert_empty "every container sets a memory limit" \
    "$(yq -N 'select(.kind == "Deployment") | .spec.template.spec.containers[] | select(.resources.limits.memory == null) | .name' "$manifest")"

  assert_empty "every container sets a memory request" \
    "$(yq -N 'select(.kind == "Deployment") | .spec.template.spec.containers[] | select(.resources.requests.memory == null) | .name' "$manifest")"

  assert_empty "every container sets readOnlyRootFilesystem" \
    "$(yq -N 'select(.kind == "Deployment") | .spec.template.spec.containers[] | select(.securityContext.readOnlyRootFilesystem != true) | .name' "$manifest")"

  assert_empty "every container disables privilege escalation" \
    "$(yq -N 'select(.kind == "Deployment") | .spec.template.spec.containers[] | select(.securityContext.allowPrivilegeEscalation != false) | .name' "$manifest")"

  assert_empty "every container drops all capabilities" \
    "$(yq -N 'select(.kind == "Deployment") | .spec.template.spec.containers[] | select(.securityContext.capabilities.drop[0] != "ALL") | .name' "$manifest")"

  assert_empty "no pod mounts a ServiceAccount token" \
    "$(yq -N 'select(.kind == "Deployment") | select(.spec.template.spec.automountServiceAccountToken != false) | .metadata.name' "$manifest")"

  # --- Pod Security Standards, `restricted` profile --------------------------------------------
  #
  # The chart already satisfies every field of the `restricted` profile, and these assertions are
  # what keep that true rather than incidental. #416 adds the namespace label that *enforces* it;
  # until then nothing rejects a violation at admission, so a workload could drift into
  # non-compliance and only fail on the day the label lands. The four container-level fields are
  # asserted above; these are the pod-level ones.
  #
  # https://kubernetes.io/docs/concepts/security/pod-security-standards/

  assert_empty "every pod sets seccompProfile RuntimeDefault" \
    "$(yq -N 'select(.kind == "Deployment") | select(.spec.template.spec.securityContext.seccompProfile.type != "RuntimeDefault") | .metadata.name' "$manifest")"

  assert_empty "no pod uses a host namespace" \
    "$(yq -N '.. | select(has("hostNetwork") or has("hostPID") or has("hostIPC")) | [.hostNetwork, .hostPID, .hostIPC] | .[] | select(. == true)' "$manifest")"

  assert_empty "no container requests a host port" \
    "$(yq -N '.. | select(has("hostPort")) | .hostPort' "$manifest")"

  assert_empty "no container is privileged" \
    "$(yq -N '.. | select(has("privileged")) | select(.privileged == true) | .privileged' "$manifest")"

  # `restricted` permits configMap, csi, downwardAPI, emptyDir, ephemeral, persistentVolumeClaim,
  # projected and secret — and nothing else. hostPath is the one that matters: it is a mount of the
  # node's filesystem, and it is how a pod escapes being a pod.
  assert_empty "no pod mounts a volume type outside the restricted set" \
    "$(yq -N 'select(.kind == "Deployment") | .spec.template.spec.volumes[] | keys | .[] | select(. != "name" and . != "configMap" and . != "csi" and . != "downwardAPI" and . != "emptyDir" and . != "ephemeral" and . != "persistentVolumeClaim" and . != "projected" and . != "secret")' "$manifest")"

  # --- Images ---------------------------------------------------------------------------------

  assert_nonempty "some workload declares an image" \
    "$(yq -N 'select(.kind == "Deployment") | .spec.template.spec.containers[].image' "$manifest")"

  assert_empty "no image uses a floating tag" \
    "$(yq -N '.. | select(has("image")) | .image' "$manifest" | grep -E ':(latest|head|canary|main|edge)$' || true)"

  assert_empty "every image carries an explicit tag" \
    "$(yq -N '.. | select(has("image")) | .image' "$manifest" | grep -Ev ':[^/:]+$' || true)"

  # --- Namespaces belong to whoever installs the chart ----------------------------------------
  #
  # Flux's HelmRelease sets targetNamespace, and a hardcoded metadata.namespace would silently win
  # over it (#414).

  assert_empty "no template hardcodes metadata.namespace" \
    "$(yq -N 'select(.metadata.namespace != null) | .kind + "/" + .metadata.name' "$manifest")"
}

# --- Published values files must not pin an image tag ------------------------------------------
#
# Not a render assertion — this reads the values files themselves, because what it checks is exactly
# what rendering destroys. Every component's `image.tag` defaults to "" and falls back to
# `.Chart.AppVersion`, and that fallback is the whole mechanism keeping the chart and the images in
# step (#264): one number stamped at build time reaches all four artifacts. An explicit tag in a
# published values file silently opts that component out — the render still looks entirely correct,
# with a plausible tag on every image, while one workload is pinned to a version nobody chose.
#
# values-k3d.yaml is deliberately exempt: it pins `dev` for locally built images, and it never
# leaves a laptop. Every HelmRelease is checked, because those are what deploy.
check_values_files() {
  current_case="values files"
  printf '\n== values files ==\n'

  local file component
  for component in bff importer frontend; do
    assert_equals "values.yaml: $component.image.tag is empty, so it falls back to appVersion" \
      "" "$(yq -N ".${component}.image.tag // \"\"" "$CHART_DIR/values.yaml")"
  done

  for file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
    [[ -e "$file" ]] || continue
    for component in bff importer frontend; do
      assert_equals "$(basename "$(dirname "$file")")/helm-release.yaml: $component.image.tag is empty" \
        "" "$(yq -N ".spec.values.${component}.image.tag // \"\"" "$file")"
    done
  done
}

# --- A ClusterIssuer needs cert-manager to already be there ------------------------------------
#
# Also not a render assertion: what it checks is a relationship *between* files, which no single
# render can see. The chart's ClusterIssuer template renders a `cert-manager.io/v1` object, and the
# API server rejects an unknown kind — so a cluster whose application HelmRelease sets
# `certManager.clusterIssuer.create: true` without a `dependsOn` installs nothing at all. Not the
# issuer: the whole release, workloads included.
#
# It fails on the very first bootstrap of a new cluster and looks like a chart bug, which is the
# expensive kind of failure. #265 added the dependency; this is what keeps it.
check_cluster_dependencies() {
  current_case="cluster dependencies"
  printf '\n== cluster dependencies ==\n'

  local file cluster creates depends
  for file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
    [[ -e "$file" ]] || continue
    cluster="$(basename "$(dirname "$file")")"

    creates="$(yq -N '.spec.values.certManager.clusterIssuer.create // false' "$file")"
    [[ "$creates" == "true" ]] || continue

    depends="$(yq -N '.spec.dependsOn[].name // ""' "$file")"
    if [[ -n "$(printf '%s' "$depends" | tr -d '[:space:]')" ]]; then
      pass "$cluster: creates a ClusterIssuer and declares dependsOn ($(printf '%s' "$depends" | tr '\n' ' '))"
    else
      fail "$cluster: creates a ClusterIssuer but declares no dependsOn" \
        "the release renders a cert-manager.io/v1 kind; without cert-manager installed first the
whole release fails on an unknown kind, not just the issuer"
    fi
  done

  # Every chart this repository installs from a HelmRepository must name one version, not a range.
  # A range lets a new upstream release reach the cluster with no diff, no review and no commit —
  # which is the property GitOps exists to remove, and it would be silent.
  local release version
  for release in "$CLUSTERS_DIR"/*/*.yaml; do
    [[ -e "$release" ]] || continue
    [[ "$(yq -N '.kind // ""' "$release")" == "HelmRelease" ]] || continue
    version="$(yq -N '.spec.chart.spec.version // ""' "$release")"
    [[ -n "$version" ]] || continue

    cluster="$(basename "$(dirname "$release")")/$(basename "$release")"
    if [[ "$version" =~ ^v?[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
      pass "$cluster: chart version is pinned exactly ($version)"
    else
      fail "$cluster: chart version '$version' is a range, not a pin" \
        "an upstream release could then reach the cluster with no commit and no review"
    fi
  done
}

render_case() {
  local name="$1"
  shift

  current_case="$name"
  local manifest
  # An explicit path with its own XXXXXX rather than `mktemp -t <prefix>`: BSD mktemp treats the
  # argument as a prefix and appends the random part, while GNU coreutils requires the X's to be
  # there already and fails with "too few X's in template". This form works on both.
  manifest="$(mktemp "${TMPDIR:-/tmp}/event-junkie-render.XXXXXX")"
  # shellcheck disable=SC2064  # expand $manifest now, while it is still set
  trap "rm -f '$manifest'" RETURN

  printf '\n== %s ==\n' "$name"
  # The case name is a human-readable heading; the release name has to satisfy Helm's regex
  # (lowercase alphanumeric and hyphens). Deriving one from the other rather than reusing it means a
  # label like "k3d (HelmRelease)" cannot fail the render for a reason that has nothing to do with
  # the chart — which it did, once.
  local release
  release="assert-$(printf '%s' "$name" | tr '[:upper:]' '[:lower:]' | tr -cs 'a-z0-9' '-' | sed 's/-*$//')"
  helm template "$release" "$CHART_DIR" "$@" >"$manifest"
  run_assertions "$manifest"
}

# Renders the chart with a HelmRelease's own `spec.values`, so every assertion below applies to what
# Flux will hand Helm — not to an approximation of it. `spec.values` is extracted rather than the
# whole object because `helm template` wants a values document, and a HelmRelease is not one.
render_helm_release() {
  local file="$1" name values
  # Suffixed, because `deploy/clusters/k3d` and `values-k3d.yaml` are two different configurations
  # of the same cluster — published artifacts via Flux, versus locally built images via `helm
  # install` — and an unlabelled duplicate heading in the output would read as a bug.
  name="$(basename "$(dirname "$file")") (HelmRelease)"
  values="$(mktemp "${TMPDIR:-/tmp}/event-junkie-values.XXXXXX")"
  yq -N '.spec.values' "$file" >"$values"
  render_case "$name" --values "$values"
  rm -f "$values"
}

main() {
  command -v helm >/dev/null || { echo "helm is not installed" >&2; exit 127; }
  command -v yq >/dev/null || { echo "yq is not installed" >&2; exit 127; }

  check_values_files
  check_cluster_dependencies

  render_case "default" "${BASE_OVERRIDES[@]}"

  local values_file name
  for values_file in "$CHART_DIR"/values-*.yaml; do
    [[ -e "$values_file" ]] || continue
    name="$(basename "$values_file" .yaml)"
    name="${name#values-}"
    render_case "$name" --values "$values_file"
  done

  local release_file
  for release_file in "$CLUSTERS_DIR"/*/helm-release.yaml; do
    [[ -e "$release_file" ]] || continue
    render_helm_release "$release_file"
  done

  printf '\n'
  if ((failures > 0)); then
    printf '%d assertion(s) failed\n' "$failures" >&2
    exit 1
  fi
  printf 'all assertions passed\n'
}

main "$@"
