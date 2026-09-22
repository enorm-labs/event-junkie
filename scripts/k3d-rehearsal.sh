#!/usr/bin/env bash
#
# k3d-rehearsal.sh — run the whole stack on a local Kubernetes and prove it works end to end.
#
# Deterministic mechanics behind the /k3d-rehearsal skill: nobody re-derives the k3d, helm and kubectl
# incantations, and teardown is one command. Per ADR-012 the chart and images that run here are the
# ones that run on Hetzner k3s.
#
# Usage: scripts/k3d-rehearsal.sh <command>
#   up            Build images, create the cluster, install the chart, wait for it to converge
#   verify        Assert the ingress split from outside the cluster (routing + the negative cases)
#   import [slug] Seed one venue and source through the admin API and run a real import
#   chain         The end-to-end assertion: a scraped event comes back out of /api/events
#   test          `helm test` — the chart's two hooks: the connection test and the k6 smoke
#   status        What exists right now: cluster, pods, release
#   down          Uninstall, delete the cluster, drop the database, restore the kube context
#   all           up → verify → import → chain → test → down, stopping at the first failure
#   Through Flux, with the chart already published in GHCR:
#   flux-up       Create the cluster, install the Flux controllers, apply deploy/clusters/k3d
#   flux-verify   Assert what Flux pulled: a snapshot, from GHCR, one tag, tests passed
#   flux-trap     Remove the `-0` from the semver range and watch it stop matching (#414)
#   flux-break    Break the release on purpose and watch it roll back
#   flux-all      flux-up → flux-verify → flux-trap → flux-break → down
#
# `all` and `flux-all` answer different questions and must not share a cluster: `all` installs the
# working tree's chart with images built seconds ago ("does my change work?"); `flux-all` installs
# the chart published in GHCR through the controllers that run on Hetzner ("does the delivery work?").
#
# THE SAFETY RULE: every kubectl and helm call passes --context/--kube-context explicitly, from a
# constant below. `k3d cluster create` switches the *active* context, and a developer kubeconfig
# usually holds other clusters — production ones among them. `down` puts the context back.
#
# Requires: k3d, kubectl, helm, docker, yq, and a JDK + Node for the image builds. The opt-in airgap
# preload (K3D_PRELOAD_IMAGES=1) wants jq, and `crane` if it can have it.

set -euo pipefail

CLUSTER=event-junkie
CONTEXT="k3d-${CLUSTER}"
RELEASE=event-junkie
CHART=deploy/charts/event-junkie
VALUES="${CHART}/values-k3d.yaml"
# Applied on top of VALUES when K3D_IMAGES=1. Off by default: it needs the compose stack's MinIO and
# two Secrets.
IMAGES_VALUES="${CHART}/values-k3d-images.yaml"
IMAGES="${K3D_IMAGES:-0}"
# The rehearsal gets a database of its own, never the development one: installing the chart runs
# Flyway, and the in-cluster importer would compete with a local `bootRun` over one schema.
DB=event_junkie_k3d
PG_CONTAINER=event-junkie-postgres-1
# The Flux half (#414): the CRs applied to the cluster, and the namespace the HelmRelease targets.
FLUX_DIR=deploy/clusters/k3d
FLUX_NS=event-junkie
HOST_HEADER='Host: event-junkie.localhost'
BASE=localhost:8080
STATE_DIR=build/k3d-rehearsal

log()  { printf '\n\033[1m== %s\033[0m\n' "$*"; }
info() { printf '   %s\n' "$*"; }
ok()   { printf '   \033[32mok\033[0m   %s\n' "$*"; }
bad()  { printf '   \033[31mFAIL\033[0m %s\n' "$*" >&2; FAILURES=$((FAILURES + 1)); }
# A third outcome, for an assertion that could not be made rather than one that failed (#693): "the
# importer persisted nothing, so there is nothing to measure". Not counted into FAILURES.
skip() { printf '   \033[33mSKIP\033[0m %s\n' "$*"; }
die()  { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

FAILURES=0

require() {
  for t in "$@"; do command -v "$t" >/dev/null || die "$t is not installed"; done
}

# Refuses to go near a cluster this script did not create.
guard_context() {
  case "$CONTEXT" in
    k3d-*) ;;
    *) die "refusing to act on context '$CONTEXT' — this script only ever touches a k3d cluster" ;;
  esac
  kubectl config get-contexts -o name | grep -qx "$CONTEXT" \
    || die "context '$CONTEXT' does not exist — run '$0 up' first"
}

k()  { kubectl --context "$CONTEXT" "$@"; }
h()  { helm --kube-context "$CONTEXT" "$@"; }
# `flux` resolves the current kubeconfig context exactly like `helm install --dry-run` does.
f()  { flux --context "$CONTEXT" "$@"; }
psql_() { docker exec "$PG_CONTAINER" psql -U admin "$@"; }

# Assert that an image tarball carries the images it claims to, and that any images named as extra
# arguments are among them. `docker save` can exit 0 having written a manifest and config with no
# layer blobs (#533, Docker 29.7.2 with the containerd store); k3s's import reports success on such a
# tarball, and the pods then fail with the same x509 error a node that cannot pull produces, so the
# evidence points at the network. Format-agnostic: both writers put a `manifest.json` at the root
# whose `Config` and `Layers` entries are member paths. Proves presence, not integrity. Returns rather
# than dying: a cached tarball is rebuilt, a freshly fetched one is a hard stop.
verify_airgap_tar() {
  local tar="$1"
  shift
  local manifest members refs missing tags want incomplete=0

  manifest="$(tar -xOf "$tar" manifest.json 2>/dev/null)" || manifest=""
  [ -n "$manifest" ] || { info "$tar has no manifest.json — it is not an image archive"; return 1; }
  members="$(tar -tf "$tar" 2>/dev/null)" || { info "could not read $tar"; return 1; }

  # One `image<TAB>member` line per blob the manifest references, its config included.
  refs="$(printf '%s\n' "$manifest" \
    | jq -r '.[] | (.RepoTags[0] // .Config) as $i | ([.Config] + (.Layers // []))[] | "\($i)\t\(.)"')" \
    || { info "could not read the manifest.json in $tar"; return 1; }

  missing="$(
    while IFS=$'\t' read -r image member; do
      [ -n "$member" ] || continue
      printf '%s\n' "$members" | grep -qxF "$member" || printf '%s\n' "$image"
    done <<EOF
$refs
EOF
  )"

  if [ -n "$missing" ]; then
    printf '%s\n' "$missing" | sort | uniq -c | while read -r n image; do
      info "$image is missing $n of its blobs"
    done
    incomplete=1
  fi

  # Every blob present says nothing about coverage: seven complete images pass and leave the eighth to
  # pull. The two writers disagree about the `docker.io/` prefix, so both sides are normalised.
  tags="$(printf '%s\n' "$manifest" | jq -r '.[].RepoTags[]? | sub("^(index\\.)?docker\\.io/"; "")')"
  for want in "$@"; do
    printf '%s\n' "$tags" | grep -qxF "$(printf '%s' "$want" | sed -E 's#^(index\.)?docker\.io/##')" \
      || { info "$want is not in the tarball at all"; incomplete=1; }
  done

  [ "$incomplete" = 0 ]
}

# Pull k3s's own system images on the HOST and hand them to the node as an airgap tarball, which k3s
# imports at startup from /var/lib/rancher/k3s/agent/images. Opt-in: for a node that cannot reach
# docker.io while the host can — a TLS-inspecting network, where the host trusts the interception CA
# and the node's containerd does not, and every pod sits in ContainerCreating with an x509 error on
# the *pause sandbox* image. An airgap escape hatch, not a workaround for one network; it does NOT
# install anyone's CA and must not grow into doing so.
#
# A correct preload ends with `8 images verified in …/k3s-airgap.tar`, and *verified* is the word:
# the tarball has been read back and every layer blob is in it (#533). If pods still sit in
# ContainerCreating after that, the node genuinely cannot pull (#526).
preload_images() {
  [ "${K3D_PRELOAD_IMAGES:-0}" = "1" ] || return 0
  # Scoped to the opt-in path: the preload is the only thing here that needs jq.
  require jq

  local dir="$STATE_DIR/airgap" ver url tar
  tar="$dir/k3s-airgap.tar"
  mkdir -p "$dir"

  # Ask k3d which k3s it will run rather than pinning one; k3d's default moves with its releases.
  ver="$(k3d version --output json | yq -p json '.k3s')"
  [ -n "$ver" ] && [ "$ver" != "null" ] || die "could not determine the k3s version k3d will use"

  if [ -f "$tar" ] && [ "$(cat "$dir/version" 2>/dev/null)" = "$ver" ]; then
    # Verified again rather than trusted: tarballs written before #533 carry a marker with no check
    # behind them, and re-reading costs a second. Failure rebuilds rather than dies — the cache is keyed
    # on the k3s version alone, which once made a bad tarball permanent.
    if verify_airgap_tar "$tar"; then
      info "airgap images already prepared for $ver"
      return 0
    fi
    info "the cached airgap tarball for $ver is incomplete — discarding it and fetching again"
    rm -f "$tar" "$dir/version"
  fi

  # The release publishes the canonical list; guessing image names is how one gets missed. `+` must be
  # percent-encoded in the URL.
  url="https://github.com/k3s-io/k3s/releases/download/${ver//-k3s1/%2Bk3s1}/k3s-images.txt"
  log "Preloading k3s system images for $ver (K3D_PRELOAD_IMAGES=1)"
  local images=()
  while IFS= read -r image; do
    [ -n "$image" ] || continue
    images+=("$image")
  done < <(curl -sSL --fail "$url" || die "could not fetch the k3s image list from $url")

  [ "${#images[@]}" -gt 0 ] || die "the k3s image list was empty"

  # The node runs on this host's Docker, so the daemon's platform is the node's; `crane` has to be told.
  local plat
  plat="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')" || plat=""
  [ -n "$plat" ] || die "could not determine the platform the k3d node will run on"

  # `crane` is preferred and `docker save` the fallback: `docker save` is what wrote empty images
  # (#533) and it fetches nothing anyway, while `crane` reads the registry directly and, as a static Go
  # binary, resolves TLS through the system trust store — so it works on the interception-CA network
  # this exists for, which a containerised crane would not. Verified either way.
  if command -v crane >/dev/null; then
    info "fetching ${#images[@]} images with crane ($plat)"
    crane pull --platform "$plat" "${images[@]}" "$tar" \
      || die "crane could not fetch the airgap images — if the host cannot reach docker.io either, this is the network, not the node's trust store"
  else
    info "crane not found, falling back to docker save — 'brew install crane' if the check below fails"
    local image
    for image in "${images[@]}"; do
      docker pull -q "$image" >/dev/null \
        || die "could not pull $image on the host either — this is not the node's trust store, it is the network"
    done
    docker save "${images[@]}" -o "$tar" || die "could not save the airgap tarball"
  fi

  # Discarded on failure so the next run refetches, and the version marker is written only past this
  # point so a bad tarball can never become a cached one.
  verify_airgap_tar "$tar" "${images[@]}" || {
    rm -f "$tar"
    die "the airgap tarball is incomplete — the images named above are missing content, and it has been discarded. If docker save wrote it, 'brew install crane' and run again."
  }

  printf '%s' "$ver" > "$dir/version"
  info "${#images[@]} images verified in $tar"
}

# Shared by `up` and `flux-up`: the same cluster and database, the chart installed two different ways.
create_cluster() {
  mkdir -p "$STATE_DIR"
  # Saved before k3d switches it, so `down` can put it back exactly.
  kubectl config current-context > "$STATE_DIR/previous-context" 2>/dev/null || true

  log "Creating the cluster"
  # 8080:80 publishes Traefik, which is what makes the ingress testable from the host.
  preload_images
  # k3s imports any tarball in this directory before it pulls, so the mount has to exist at creation time.
  local airgap=()
  # `@server:0;agent:0` is not decoration: without a node filter the mount does not land where k3s
  # looks, the tarball is silently ignored, and the failure is identical to the one this fixes.
  [ "${K3D_PRELOAD_IMAGES:-0}" = "1" ] && airgap=(--volume "$PWD/$STATE_DIR/airgap:/var/lib/rancher/k3s/agent/images@server:0;agent:0")
  # ${arr[@]+"${arr[@]}"} rather than "${arr[@]}": an empty array under `set -u` is only safe from bash
  # 4.4, and macOS ships 3.2. `|| die` because this runs on the left of an `&&` chain in `main`, which
  # exempts the whole function from errexit (see `main`); without it a failed creation was reported as
  # success (#692). Only stdout is redirected, so k3d's own ERRO/FATA lines still show.
  k3d cluster create "$CLUSTER" --port "8080:80@loadbalancer" --agents 1 ${airgap[@]+"${airgap[@]}"} >/dev/null \
    || die "k3d could not create the cluster — its own ERRO/FATA lines are above.
The usual cause here is something already listening on 8080, which is what the BFF binds under 'bootRun':
  lsof -nP -iTCP:8080 -sTCP:LISTEN
k3d rolls its own changes back, so there is nothing left to clean up."

  # Read into a variable and asserted rather than interpolated: a printed line is not an assertion, and
  # `cluster up ()` — an empty architecture from a `kubectl` that had just said "context was not found"
  # — is how a cluster that was never created looked like one that was (#541).
  # found" — which is what made a cluster that was never created look like one that was.
  local arch
  arch="$(k get nodes -o jsonpath='{.items[0].status.nodeInfo.architecture}' 2>/dev/null)" || arch=""
  [ -n "$arch" ] || die "the cluster reports no nodes — k3d returned 0 but context '$CONTEXT' has nothing in it"
  info "cluster up ($arch)"

  # k3d writes `host.k3d.internal` into the CoreDNS ConfigMap **after `k3d cluster create` returns** —
  # measured 7–11 seconds later (#541). Until it lands, every pod resolving the database host gets
  # `UnknownHostException`. The importer shows it (Flyway connects eagerly at startup and crash-loops);
  # the BFF's R2DBC pool connects lazily and never notices, so it presents as "the importer is flaky".
  #
  # **Wait for the write, then restart to load it, in that order.** The entry lands in the `NodeHosts`
  # key, read through a volume mount and the hosts plugin's own `reload 15s`, so the self-heal takes
  # long enough to produce several restarts and then look like it never happened. It self-heals, which
  # is worse than failing: the install succeeds and the only evidence is a restart count.
  local dns_waited=0
  until k -n kube-system get configmap coredns -o yaml 2>/dev/null | grep -q 'host\.k3d\.internal'; do
    [ "$dns_waited" -ge 120 ] && die "k3d never wrote host.k3d.internal into the CoreDNS ConfigMap (waited ${dns_waited}s).
Every pod resolving the database host would fail. Check 'kubectl -n kube-system get configmap coredns -o yaml'."
    sleep 1
    dns_waited=$((dns_waited + 1))
  done
  info "host.k3d.internal written to the CoreDNS ConfigMap after ${dns_waited}s"

  # Now the restart means something: a new pod mounts the ConfigMap as it is. `|| die`, because a
  # restart that never happened was once waited on rather than reported (#692).
  k -n kube-system rollout restart deployment coredns >/dev/null \
    || die "could not restart CoreDNS — it would come back on the config without host.k3d.internal"
  k -n kube-system rollout status deployment coredns --timeout=120s >/dev/null \
    || die "CoreDNS did not roll out — every pod resolving host.k3d.internal will fail"
  info "CoreDNS restarted onto it"

  # k3s installs its bundled Traefik through a HelmChart CR reconciled asynchronously, so `k3d cluster
  # create` returns BEFORE `traefik.io/v1alpha1` is a kind the API server knows. Install inside that
  # window and Helm fails the whole release with "no matches for kind Middleware" — nothing is
  # installed at all. Reachable since #286 turned `noindex` on for the rehearsal, which is the first
  # Traefik kind the chart rendered here. Polled, because `kubectl wait` cannot wait for a resource that
  # does not exist yet.
  log "Waiting for Traefik's CRDs — the chart renders Middleware objects and Helm resolves kinds up front"
  local waited=0
  until k get crd middlewares.traefik.io >/dev/null 2>&1; do
    [ "$waited" -ge 180 ] && die "Traefik CRDs never appeared (waited ${waited}s).
A pod stuck in ContainerCreating on an image pull is the usual cause — check
'kubectl -n kube-system get pods' and whether this machine can reach docker.io."
    sleep 5
    waited=$((waited + 5))
  done
  # Polled rather than `kubectl wait`, because **both** of its `--for` forms fail instantly here (#696).
  # The poll above returns the moment the *object* exists, which can be before the status subresource
  # is populated, and at that instant `.status.conditions` is explicitly **null**: `--for=condition`
  # exits 1 with `accessor error: <nil> is of the type <nil>`, and `--for='jsonpath={...}=True'` exits
  # 1 with `<nil> is not array or slice`. Both measured on kubectl 1.36.4 against a resource patched to
  # `conditions: null`; do not swap this back for either. `kubectl get -o jsonpath` fails the same way
  # and here it is harmless: a failed read is empty, empty is not True, the loop goes round again.
  local jsonpath='{.status.conditions[?(@.type=="Established")].status}' established=0
  until [ "$(k get crd middlewares.traefik.io -o jsonpath="$jsonpath" 2>/dev/null)" = "True" ]; do
    # The elapsed total is in the message, so this can never describe a wait that did not happen.
    [ "$established" -ge 60 ] && die "the Middleware CRD exists but was still not Established ${established}s later (${waited}s into the wait).
'kubectl get crd middlewares.traefik.io -o yaml' shows its status; a null .status.conditions means the API server never finished registering it."
    sleep 1
    established=$((established + 1))
  done
  # Both stages: reporting only the existence poll would print "ready after 0s" for a wait that spent
  # seconds becoming Established.
  info "Traefik CRDs ready after $((waited + established))s"
}

# Creates the rehearsal's own empty database and the credentials Secret. `namespace` decides where the
# Secret lands, and a Secret in the wrong namespace fails the release for a reason that looks like Flux.
prepare_database() {
  local namespace="${1:-default}"
  log "Database and secret"
  docker compose up -d >/dev/null 2>&1
  # `docker compose up -d` returns when the container is *started*, not when PostgreSQL accepts
  # connections; the very next psql call fails with a socket error that reads like a misconfiguration.
  local i
  for i in $(seq 1 30); do
    if docker exec "$PG_CONTAINER" pg_isready -U admin -d postgres >/dev/null 2>&1; then break; fi
    [ "$i" = 30 ] && die "PostgreSQL did not become ready within 30s"
    sleep 1
  done
  # On an empty volume the image initialises with a throwaway server that answers `pg_isready`, then
  # restarts; a statement sent in between dies with `terminating connection due to administrator
  # command`, so it retries.
  for i in $(seq 1 15); do
    if psql_ -d postgres -c "DROP DATABASE IF EXISTS $DB;" -c "CREATE DATABASE $DB OWNER admin;" >/dev/null 2>&1; then break; fi
    [ "$i" = 15 ] && die "could not create $DB within 30s of PostgreSQL reporting ready"
    sleep 2
  done
  k create namespace "$namespace" >/dev/null 2>&1 || true
  k -n "$namespace" create secret generic events-db \
    --from-literal=username=admin --from-literal=password=admin >/dev/null
  info "database $DB created empty; secret events-db created in namespace $namespace"

  [ "$IMAGES" = 1 ] || return 0
  # The two Secrets the image path needs, created out of band in every real environment (SECRETS.md).
  # `secretKeyRef` is not optional, so values that land ahead of a Secret leave the pod in
  # CreateContainerConfigError.
  k -n "$namespace" create secret generic event-junkie-images \
    --from-literal=IMAGE_STORAGE_ACCESS_KEY=minioadmin \
    --from-literal=IMAGE_STORAGE_SECRET_KEY=minioadmin >/dev/null
  # A shared secret between two containers, not a credential to anything; still 64 hex characters,
  # because imgproxy parses it as hex.
  local key salt
  key="$(printf '61%.0s' $(seq 1 32))"
  salt="$(printf '62%.0s' $(seq 1 32))"
  k -n "$namespace" create secret generic event-junkie-imgproxy \
    --from-literal="IMGPROXY_KEY=$key" --from-literal="IMGPROXY_SALT=$salt" >/dev/null
  info "secrets event-junkie-images and event-junkie-imgproxy created"
}

cmd_up() {
  require k3d kubectl helm docker yq

  # EVERY FALLIBLE STEP BELOW CARRIES AN EXPLICIT `|| die`, AND THAT IS NOT BELT-AND-BRACES: `main`
  # runs this on the left of an `&&` chain, and a command in an AND-list is exempt from errexit for the
  # whole function, recursively. Without these a release that installed nothing was reported as success
  # and four assertions measured an empty cluster (#525). `up` builds a precondition and fails fast;
  # `verify` measures and accumulates into FAILURES.
  log "Building the four images"
  ./gradlew -q :events-bff:bootJarLayers :events-importer:bootJarLayers \
    || die "the Gradle build failed — there is no jar to put in an image"
  npm --prefix events-frontend run build >/dev/null \
    || die "the frontend build failed — there is no bundle to serve"
  local rev; rev="$(git rev-parse HEAD)"
  local ver; ver="$(grep '^version=' gradle.properties | cut -d= -f2-)"
  for m in bff importer; do
    docker buildx build -f "events-$m/Dockerfile" "events-$m/build/docker" \
      --build-arg "VERSION=$ver" --build-arg "REVISION=$rev" \
      -t "localhost/event-junkie/$m:dev" --load --quiet >/dev/null \
      || die "could not build the $m image"
    info "built localhost/event-junkie/$m:dev"
  done
  docker buildx build events-frontend --build-arg "VERSION=$ver" --build-arg "REVISION=$rev" \
    -t localhost/event-junkie/frontend:dev --load --quiet >/dev/null \
    || die "could not build the frontend image"
  info "built localhost/event-junkie/frontend:dev"
  # The meta-injection sidecar (#287), from the same `npm run build`.
  docker buildx build events-frontend -f events-frontend/Dockerfile.injector \
    --build-arg "VERSION=$ver" --build-arg "REVISION=$rev" \
    -t localhost/event-junkie/injector:dev --load --quiet >/dev/null \
    || die "could not build the injector image"
  info "built localhost/event-junkie/injector:dev"

  create_cluster
  k3d image import -c "$CLUSTER" \
    localhost/event-junkie/bff:dev localhost/event-junkie/importer:dev localhost/event-junkie/frontend:dev \
    localhost/event-junkie/injector:dev >/dev/null \
    || die "could not import the images into the cluster — every pod would then try to pull them from a registry that does not have them"
  info "images imported"

  prepare_database default

  # THE ONE THAT MATTERS. Helm resolves every kind up front, so an unknown one fails the entire release
  # — nothing is installed — in a sentence that scrolls away behind whatever ran next.
  log "Installing the chart"
  local values_args=(--values "$VALUES")
  if [ "$IMAGES" = 1 ]; then
    values_args+=(--values "$IMAGES_VALUES")
    info "image caching enabled — imgproxy sidecar, MinIO on the host, serving on"
  fi
  h install "$RELEASE" "$CHART" "${values_args[@]}" --wait --timeout 5m >/dev/null \
    || die "the release did not install — nothing below this point would be measuring the chart"
  # Informational, and before the assertion below, because it succeeds whether or not anything is
  # running and its status must never be what this function returns.
  k get pods -l "app.kubernetes.io/instance=$RELEASE" --no-headers | sed 's/^/   /'

  # `--wait` establishes Ready, and Ready is not the bar: the rehearsal asks for Ready **and no
  # restarts**, because a pod that recovered after crashing is a different result — #541 survived two
  # rehearsals with four importer restarts because the only evidence was a column printed for a human.
  # `bad` rather than `die`: a restart count is a measurement, so the rest of the rehearsal still runs.
  # Summed across containers, so a workload that gains a sidecar stays covered.
  local restarted
  restarted="$(k get pods -l "app.kubernetes.io/instance=$RELEASE" \
    -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[*].restartCount}{"\n"}{end}' \
    | awk '{ s = 0; for (i = 2; i <= NF; i++) s += $i; if (s > 0) print $1, s }')"
  if [ -z "$restarted" ]; then
    ok "all pods Ready with no restarts"
  else
    # A here-string, NOT a pipe: `while read` on the right of a pipe runs in a subshell, and every
    # FAILURES increment inside it would be discarded.
    local pod count
    while read -r pod count; do
      bad "$pod restarted ${count}x before becoming Ready — Ready is not the bar (#544). 'kubectl logs --previous' will say why"
    done <<< "$restarted"
  fi
}

# Every negative assertion here checks the CONTENT TYPE, not the status code: nginx serves the SPA for
# any unmatched path, so `/actuator/health` through the ingress returns 200 and a status-only test
# would keep passing if actuator were genuinely exposed.
cmd_verify() {
  guard_context
  local code type
  probe() { # probe <path> -> sets code/type
    code="$(curl -s -o /dev/null -w '%{http_code}' -H "$HOST_HEADER" --max-time 10 "$BASE$1")"
    type="$(curl -s -o /dev/null -w '%{content_type}' -H "$HOST_HEADER" --max-time 10 "$BASE$1")"
  }

  # The negatives prove nothing if the positives fail, and until #544 nothing enforced it: with the
  # ingress misrouting, `/actuator/health` answers Traefik's error page and `/api/admin/sources` answers
  # 404, both "ok" and neither about the security property they are named for. `unproven` does NOT
  # touch FAILURES: the positive that failed already counted the outage.
  local positives=ok
  unproven() { printf '   \033[33m----\033[0m %s — unproven, the positive routing above failed\n' "$*"; }
  ok_if_routed() { if [ "$positives" = ok ]; then ok "$@"; else unproven "$@"; fi; }

  log "Positive routing — these must pass, or the negatives below prove nothing"
  probe /
  if [ "$code" = 200 ] && [ "${type#text/html}" != "$type" ]; then
    ok "/ serves the SPA"
  else
    bad "/ -> $code $type"
    positives=broken
  fi

  probe /api/events
  if [ "$code" = 200 ] && [ "${type#application/json}" != "$type" ]; then
    ok "/api/events reaches the BFF"
  else
    bad "/api/events -> $code $type"
    positives=broken
  fi

  log "Negative routing — the security properties"
  probe /actuator/health
  if [ "${type#text/html}" != "$type" ]; then
    ok_if_routed "/actuator/health is the SPA fallback, not actuator"
  else
    bad "/actuator/health returned $type — actuator may be exposed"
  fi

  probe /api/admin/sources
  if [ "$code" = 404 ]; then
    ok_if_routed "/api/admin/** does not reach the importer (BFF 404)"
  else
    bad "/api/admin/sources -> $code, expected 404"
  fi

  # The one thing no render assertion can establish (#286): the chart mounts a ConfigMap over the
  # `robots.txt` and `sitemap.xml` baked into the image with `subPath`, and a `subPath` naming a key the
  # ConfigMap lacks mounts an EMPTY DIRECTORY over the file rather than failing. The pod stays Ready, so
  # the only evidence is the bytes nginx hands back: the image's copy says `Allow: /` and names a
  # production `Sitemap:`, the mounted one says `Disallow: /`.
  #
  # EVERY ASSERTION BELOW IS PHRASED AS AN ABSENCE, AND AN ABSENCE IS TRUE OF NOTHING AT ALL — the first
  # version reported passes against an ingress answering 000. Each fetch establishes a real 200 with a
  # body first. Not gated on `positives`: `fetched` establishes a stronger precondition than "the
  # ingress routes /".
  log "The noindex body half — proves the subPath mount reached nginx, not just the manifest"
  fetched() { # fetched <path> -> sets body; false if there was no real response to judge
    body="$(curl -s -H "$HOST_HEADER" --max-time 10 "$BASE$1")"
    code="$(curl -s -o /dev/null -w '%{http_code}' -H "$HOST_HEADER" --max-time 10 "$BASE$1")"
    if [ "$code" != 200 ] || [ -z "$body" ]; then
      bad "$1 -> $code and $(printf '%s' "$body" | wc -c | tr -d ' ') bytes — nothing to assert on"
      return 1
    fi
    return 0
  }

  local body
  if fetched /robots.txt; then
    # The image's copy says `Allow: /` and carries a `Sitemap:` line; the mounted one says `Disallow: /`.
    if printf '%s' "$body" | grep -qE '^Disallow: /$'; then
      ok "/robots.txt is the mounted disallow-all, not the image's copy"
    else
      bad "/robots.txt has no 'Disallow: /' — the image's allow-all copy is being served"
    fi
    if printf '%s' "$body" | grep -qi 'sitemap:'; then
      bad "/robots.txt still names a sitemap, which can only be production's"
    else
      ok "/robots.txt names no sitemap"
    fi
  fi

  if fetched /sitemap.xml; then
    # Positive first, so the negative below cannot pass on an error page that happens to lack <loc>.
    if printf '%s' "$body" | grep -q '<urlset'; then
      ok "/sitemap.xml is a sitemap"
    else
      bad "/sitemap.xml is not a urlset at all"
    fi
    if printf '%s' "$body" | grep -q '<loc>'; then
      bad "/sitemap.xml lists URLs — it is the build's copy, naming production"
    else
      ok "/sitemap.xml lists nothing"
    fi
  fi
}

cmd_import() {
  guard_context
  # One import of one venue, once (ADR-007). AMT by default; under K3D_IMAGES it is Cassiopeia, because
  # AMT often publishes nothing upcoming — fine for the chain (#693), useless for the image path, which
  # needs a persisted event with an `image_url`.
  local slug name venue_url source_url source_type
  if [ "$IMAGES" = 1 ]; then
    slug="${1:-cassiopeia}"
    name="Cassiopeia"; source_type="CASSIOPEIA"
    venue_url="https://cassiopeia-berlin.de"
    source_url="https://cassiopeia-berlin.de/club"
  else
    slug="${1:-amt}"
    name="AMT"; source_type="AMT"
    venue_url="https://www.club-amt.berlin"
    source_url="https://www.club-amt.berlin/events"
  fi
  log "Seeding one source and running a real import (one small venue, once — ADR-007)"
  k port-forward "svc/${RELEASE}-importer" 18081:8081 >/dev/null 2>&1 &
  local pf=$!
  # shellcheck disable=SC2064  # expand $pf now
  trap "kill $pf 2>/dev/null || true" RETURN
  local api=localhost:18081/api/admin

  # A poll, not a fixed sleep (#541's class of defect, at lower stakes). Any HTTP status is the evidence
  # wanted: it proves the tunnel is open; curl's 000 is the only value that means "not yet".
  local waited=0
  until [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$api/venues")" != 000 ]; do
    kill -0 "$pf" 2>/dev/null || die "the port-forward to ${RELEASE}-importer died after ${waited}s — 'kubectl port-forward' would have said why on stderr, which this discards"
    [ "$waited" -ge 60 ] && die "the importer never answered on :18081 after ${waited}s — the port-forward is up but nothing is serving behind it"
    sleep 1
    waited=$((waited + 1))
  done
  info "importer answering on :18081 after ${waited}s"

  # The venue's address is not under test; the slug the importer derives from `name` is.
  local vid
  vid="$(curl -sS -X POST "$api/venues" -H 'Content-Type: application/json' -d "{
    \"name\":\"$name\",\"city\":\"Berlin\",\"websiteUrl\":\"$venue_url\"}" | yq -p json '.id')"
  if [ -z "$vid" ] || [ "$vid" = "null" ]; then die "venue POST failed"; fi
  info "venue id $vid ($name)"

  curl -sS -X POST "$api/event-sources" -H 'Content-Type: application/json' -d "{
    \"venueId\":$vid,\"name\":\"$name\",\"url\":\"$source_url\",
    \"sourceType\":\"$source_type\",\"enabled\":true,\"importIntervalMinutes\":1440,\"maxRetries\":3}" >/dev/null
  curl -sS -X POST "$api/event-sources/$slug/import" -o /dev/null
  info "import triggered for '$slug'"

  local i st
  for i in $(seq 1 20); do
    sleep 3
    st="$(curl -s "$api/event-sources/$slug" | yq -p json '.status')"
    case "$st" in
      SUCCESS) ok "import settled after $((i * 3))s: $st"; return 0 ;;
      FAILED)  bad "import settled after $((i * 3))s: $st"; return 1 ;;
    esac
  done
  bad "import still '$st' after 60s"
}

# The single acceptance criterion for the whole rehearsal: importer → PostgreSQL → BFF → Traefik →
# here. Every other check can pass with the pieces working only in isolation.
cmd_chain() {
  guard_context
  log "The chain: a scraped event coming back out through the ingress"
  local sources rows reported titles
  # Read from the database, not the admin API: `chain` owns no port-forward. Summed rather than looked
  # up by slug.
  sources="$(psql_ -d "$DB" -tAc 'select count(*) from events.event_source' | tr -d ' ')"
  rows="$(psql_ -d "$DB" -tAc 'select count(*) from events.event' | tr -d ' ')"
  reported="$(psql_ -d "$DB" -tAc 'select coalesce(sum(last_event_count), 0) from events.event_source' | tr -d ' ')"

  # Three outcomes, and only one is a defect in the stack; before #693 all three printed "no rows … run
  # 'import' first", wrong in two of them.
  if [ "${sources:-0}" -eq 0 ]; then
    # Nothing has been seeded in this database.
    bad "no event sources in $DB — run '$0 import' first"
    return 1
  elif [ "${rows:-0}" -gt 0 ]; then
    ok "$rows event rows written by the in-cluster importer"
  elif [ "${reported:-0}" -gt 0 ]; then
    # The importer says it persisted events and the database has none: the chain breaking between them.
    bad "the importer reported $reported event(s) persisted and $DB has none — the chain is broken between the importer and PostgreSQL"
    return 1
  else
    # Nothing was persisted, so nothing downstream to measure. `EventUpsertService` drops the past side,
    # so a venue whose next month is unpublished produces a SUCCESS that writes nothing. Not a failure —
    # and not an `ok` either, because a scraper that has genuinely stopped finding anything lands here too.
    skip "the importer persisted no events, so the chain was not exercised"
    info "expected when the seeded venue has nothing upcoming — that is what a SUCCESS writing nothing means (#693)"
    info "a scraper that has stopped finding anything looks identical here; 'Dropped N past event(s)' in the importer's log is what tells them apart"
    return 0
  fi

  titles="$(curl -s -H "$HOST_HEADER" "$BASE/api/events?size=3" | yq -p json '.content | length')"
  if [ "${titles:-0}" -gt 0 ]; then
    ok "$titles events served through the ingress"
  else
    bad "the BFF returned no events through the ingress"
  fi
  curl -s -H "$HOST_HEADER" "$BASE/api/events?size=3" \
    | yq -p json '.content[] | "     - " + .title' 2>/dev/null || true
}

# The image path, end to end: poster fetched, stored in MinIO, each width and format from the imgproxy
# sidecar, one served back through Traefik. **The step the three staging defects would each have
# failed** — `helm template` passed for all three.
cmd_images() {
  guard_context
  if [ "$IMAGES" != 1 ]; then
    skip "image caching not enabled — re-run with K3D_IMAGES=1"
    return 0
  fi
  log "The image path: fetch, store, derive, serve"

  # The importer's pass is on a five-minute tick, so polled rather than slept.
  local i variants=0
  for i in $(seq 1 40); do
    variants="$(psql_ -d "$DB" -tAc 'select count(*) from events.cached_image_variant' | tr -d ' ')"
    [ "${variants:-0}" -gt 0 ] && break
    sleep 15
  done

  if [ "${variants:-0}" -eq 0 ]; then
    local cached failed
    cached="$(psql_ -d "$DB" -tAc 'select count(*) from events.cached_image where content_hash is not null' | tr -d ' ')"
    failed="$(psql_ -d "$DB" -tAc 'select count(*) from events.cached_image where failed_at is not null' | tr -d ' ')"
    if [ "${cached:-0}" -eq 0 ] && [ "${failed:-0}" -eq 0 ]; then
      skip "the seeded venue published no image URLs, so there was nothing to cache"
      return 0
    fi
    bad "$cached image(s) stored and $failed refused, and no derivative was generated — the importer is not reaching its sidecar"
    return 1
  fi
  ok "$variants derivative(s) generated by the imgproxy sidecar"

  # Every format, not just the count: JPEG alone means imgproxy answered and the formats this design
  # exists for did not arrive.
  local formats
  formats="$(psql_ -d "$DB" -tAc "select string_agg(distinct format, ',' order by format) from events.cached_image_variant" | tr -d ' ')"
  case "$formats" in
    *avif*) ok "formats generated: $formats" ;;
    *) bad "only $formats generated — imgproxy answered but produced no AVIF, which is what ADR-020 chose it for" ;;
  esac

  # The other end: one of those objects, served from our own origin through a real Traefik.
  local hash width type
  hash="$(psql_ -d "$DB" -tAc "select c.content_hash from events.cached_image c join events.cached_image_variant v on v.cached_image_id = c.id where v.format = 'jpg' limit 1" | tr -d ' ')"
  width="$(psql_ -d "$DB" -tAc "select v.width from events.cached_image c join events.cached_image_variant v on v.cached_image_id = c.id where v.format = 'jpg' and c.content_hash = '$hash' order by v.width limit 1" | tr -d ' ')"
  type="$(curl -s -o /dev/null -w '%{content_type}' -H "$HOST_HEADER" "$BASE/api/images/$hash/$width.jpg")"
  if [ "$type" = "image/jpeg" ]; then
    ok "a derivative served through the ingress as image/jpeg"
  else
    bad "GET /api/images/$hash/$width.jpg returned '$type' rather than image/jpeg — the serving path is broken"
  fi

  # The substitution, which is what stops the browser contacting the venue.
  local served
  served="$(curl -s -H "$HOST_HEADER" "$BASE/api/events?size=20" | yq -p json '[.content[] | select(.imageUrl == "/api/images/*")] | length')"
  if [ "${served:-0}" -gt 0 ]; then
    ok "$served event(s) served an imageUrl on our own origin"
  else
    bad "no event carried an imageUrl under /api/images — the BFF is still handing out venue URLs"
  fi
}

# Piped, so `pipefail` carries helm's status out and errexit stops the run at the `&&` in `main` —
# correctly, and until #544 silently. Capture first, then judge, so the transcript names the step.
cmd_test() {
  guard_context
  log "helm test"
  local out status=0
  out="$(h test "$RELEASE" --timeout 3m 2>&1)" || status=$?
  printf '%s\n' "$out" | tail -4 | sed 's/^/   /'
  if [ "$status" -ne 0 ]; then
    bad "helm test failed (exit $status) — the chart's own test hook did not pass; the four lines above are its tail"
    return 1
  fi
  ok "helm test passed"
}

# --- The Flux half (#414) ------------------------------------------------------------------------
#
# `up` installs the working tree's chart ("does my change work?"); this installs the chart *published*
# in GHCR through the controllers that run on Hetzner ("does the delivery mechanism work?"). NOT
# `flux bootstrap`: that commits manifests and a deploy key to this repository. `flux install` gives
# the controllers; the CRs are applied from the working tree.
cmd_flux_up() {
  require k3d kubectl flux docker yq
  create_cluster
  prepare_database "$FLUX_NS"

  # EVERY FALLIBLE STEP BELOW CARRIES AN EXPLICIT `|| die`, for the reason `cmd_up` states: an AND-list
  # exempts the function from errexit. #525's lesson reached the helm half only (#544), so `f install`
  # and `k apply` could fail and the run would report "OCIRepository never became Ready".
  log "Installing the Flux controllers"
  f install >/dev/null \
    || die "flux install failed — there are no controllers, and every failure after this point would be a symptom of that rather than of the chart"
  # A count printed as a fact is how #533 and #541 hid: `0 controllers installed` prints as calmly as
  # `6`. Naming the two this rehearsal cannot work without beats counting them.
  # between versions, so an exact number would be brittle for no gain.
  for c in source-controller helm-controller; do
    k -n flux-system get "deploy/$c" >/dev/null 2>&1 \
      || die "flux install reported success without a $c — the OCIRepository/HelmRelease below would never reconcile, and would say nothing about why"
  done
  info "$(k -n flux-system get deploy -o name | wc -l | tr -d ' ') controllers installed, including source-controller and helm-controller"

  log "Applying deploy/clusters/k3d"
  k apply -k "$FLUX_DIR" >/dev/null \
    || die "kubectl apply -k $FLUX_DIR failed — the OCIRepository and HelmRelease were never created, so waiting on them below would time out on resources that do not exist"
  # Bounded and separate, so a failure names which half broke: a source that never becomes Ready is a
  # registry or range problem, a release is a chart or values problem.
  if k -n flux-system wait ocirepository/event-junkie --for=condition=Ready --timeout=2m >/dev/null 2>&1; then
    ok "OCIRepository resolved $(k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.artifact.revision}')"
  else
    bad "OCIRepository never became Ready"
    k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.conditions[*].message}' | sed 's/^/     /'
    return 1
  fi
  if k -n flux-system wait helmrelease/event-junkie --for=condition=Ready --timeout=8m >/dev/null 2>&1; then
    ok "HelmRelease reconciled"
  else
    bad "HelmRelease never became Ready"
    k -n flux-system get helmrelease event-junkie -o jsonpath='{.status.conditions[*].message}' | sed 's/^/     /'
    return 1
  fi
  k -n "$FLUX_NS" get pods --no-headers | sed 's/^/   /'
}

cmd_flux_verify() {
  guard_context
  log "What Flux actually pulled"

  local revision range
  revision="$(k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.artifact.revision}')"
  range="$(k -n flux-system get ocirepository event-junkie -o jsonpath='{.spec.ref.semver}')"
  # THE `-0` IS THE ASSERTION, NOT THE KIND OF CHART THAT RESOLVED. A snapshot is a SemVer prerelease
  # and a range without a prerelease comparator skips it silently, which is the trap `flux-trap`
  # demonstrates. Asserting "a snapshot resolved" was a proxy for that, and it is wrong for the window
  # between a release and the next merge to main: `cut-release.yml` publishes 0.23.0, which outranks
  # every snapshot before it, so the correct resolution is a release and the run failed on it (#1699).
  case "$range" in
    *-0*) ok "the range admits prereleases: $range" ;;
    *)    bad "the range is '$range' — without the -0 no snapshot can ever resolve" ;;
  esac
  case "$revision" in
    "")         bad "no artifact resolved at all" ;;
    *snapshot*) ok "resolved a snapshot: $revision" ;;
    *)          ok "resolved a release: $revision — newer than every snapshot, which is what a cut means" ;;
  esac

  local images
  images="$(k -n "$FLUX_NS" get deploy -o jsonpath='{range .items[*]}{.spec.template.spec.containers[*].image}{"\n"}{end}')"
  if printf '%s' "$images" | grep -q '^ghcr.io/enorm-labs/event-junkie/'; then
    ok "workloads run images pulled from GHCR, not side-loaded"
    printf '%s\n' "$images" | sed 's/^/     /'
  else
    bad "expected ghcr.io images, got: $images"
  fi

  # Every image tag must equal the chart's appVersion (#264's fallback). The digest behind the tag is
  # stripped first: since #1473 a published chart names each image `repo:tag@sha256:…`.
  local distinct
  distinct="$(printf '%s\n' "$images" | sed -e 's/@sha256:[0-9a-f]*$//' -e 's/.*://' | sort -u | wc -l | tr -d ' ')"
  if [ "$distinct" = 1 ]; then
    ok "all three images carry one tag — the appVersion fallback holds"
  else
    bad "$distinct distinct image tags; the chart and the images have drifted"
  fi

  # And every one of ours carries that digest (#1473). A chart built locally has none; this one came
  # from release.yml, which stamps or fails.
  local ours undigested
  ours="$(printf '%s\n' "$images" | tr ' ' '\n' | grep '^ghcr.io/enorm-labs/event-junkie/' || true)"
  undigested="$(printf '%s\n' "$ours" | grep -vc '@sha256:[0-9a-f]\{64\}$' || true)"
  if [ -n "$ours" ] && [ "$undigested" = 0 ]; then
    ok "every image of ours is pulled by digest"
  else
    bad "$undigested image(s) of ours carry no digest: the stamp in release.yml did not reach the chart"
  fi

  # Flux runs the chart's own `helm test` hooks and records the result as a condition — the in-cluster
  # smoke tests that replace the external one CI cannot run. Both, since the k3d values enable the
  # k6 hook (#1697).
  if [ "$(k -n flux-system get helmrelease event-junkie -o jsonpath='{.status.conditions[?(@.type=="TestSuccess")].status}')" = "True" ]; then
    ok "helm test ran in-cluster and passed"
  else
    bad "TestSuccess is not True — the chart's test hook did not pass"
  fi
}

# Removes the `-0` and watches the range stop seeing snapshots. The trap is silent, so the only way to
# trust the range is to see both states.
cmd_flux_trap() {
  guard_context
  log "The prerelease trap, observed rather than trusted"
  local before
  before="$(k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.artifact.revision}')"
  k -n flux-system patch ocirepository event-junkie --type=merge \
    -p '{"spec":{"ref":{"semver":">=0.0.0"}}}' >/dev/null
  f reconcile source oci event-junkie >/dev/null 2>&1 || true
  sleep 5
  local ready message after
  ready="$(k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')"
  message="$(k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.conditions[?(@.type=="Ready")].message}')"
  after="$(k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.artifact.revision}')"
  # TWO OUTCOMES, BOTH CORRECT, AND WHICH ONE DEPENDS ON WHETHER A RELEASE EXISTS YET. With only
  # snapshots published the range matches nothing at all. Once a release is out it matches that
  # release, and the trap is that every snapshot after it is invisible — the same silence, one version
  # later. Asserting only the first left this red for the window after a cut (#1699).
  if [ "$ready" = "False" ]; then
    ok "without the -0 the range matches nothing: $message"
  elif [ -n "$after" ] && [ "$after" != "$before" ]; then
    case "$after" in
      *snapshot*) bad "expected the range to stop seeing snapshots, but it resolved $after" ;;
      *)          ok "without the -0 the range falls back to a release and ignores every later snapshot: $after" ;;
    esac
  else
    bad "expected the range to stop matching, but Ready=$ready and the revision did not move ($message)"
  fi

  info "restoring the range"
  k -n flux-system patch ocirepository event-junkie --type=merge \
    -p '{"spec":{"ref":{"semver":">=0.0.0-0"}}}' >/dev/null
  f reconcile source oci event-junkie >/dev/null 2>&1 || true
  # `wait && ok` with no else was a silent stop (#544), and it left the cluster in the broken state this
  # function created on purpose.
  if k -n flux-system wait ocirepository/event-junkie --for=condition=Ready --timeout=2m >/dev/null 2>&1; then
    ok "range restored, artifact resolves again"
  else
    bad "the range was NOT restored — the OCIRepository is still not Ready, and this function left it that way"
    k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.conditions[*].message}' | sed 's/^/     /'
    return 1
  fi
}

# Breaks a release on purpose and watches the rollback — the single most valuable thing to see
# outside an incident, and #263's rehearsal had no equivalent.
cmd_flux_break() {
  guard_context
  log "Breaking the release on purpose"
  # The Deployment is NAMED, not `.items[0]` (#544): sorted order made that the bff by accident, and a
  # rename would leave this comparing an image nobody touched.
  local deploy="deploy/${RELEASE}-bff"
  local before
  before="$(k -n "$FLUX_NS" get "$deploy" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
  # An equality of two empty strings is true. Establish there is a real image before comparing it.
  if [ -z "$before" ]; then
    bad "$deploy has no image to read — there is nothing for the rollback assertion to be about"
    return 1
  fi
  info "currently running $before"

  # `timeout` and `retries: 0` keep this to about a minute; at the file's own values a failing upgrade
  # takes 5m per attempt, and a rehearsal nobody waits for is one nobody runs.
  k -n flux-system patch helmrelease event-junkie --type=merge -p '{
    "spec": {"timeout": "60s",
             "upgrade": {"remediation": {"retries": 0}},
             "values": {"bff": {"image": {"tag": "no-such-tag-0000"}}}}}' >/dev/null
  f reconcile helmrelease event-junkie >/dev/null 2>&1 || true

  local i state
  for i in $(seq 1 30); do
    sleep 5
    state="$(k -n flux-system get helmrelease event-junkie -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')"
    [ "$state" = "False" ] && break
  done
  if [ "$state" = "False" ]; then
    ok "the bad upgrade failed rather than being accepted"
  else
    bad "the release still reports Ready after a deliberately broken upgrade"
  fi

  # The property that matters: the site kept serving the last good version throughout.
  local after
  after="$(k -n "$FLUX_NS" get "$deploy" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
  if [ -z "$after" ]; then
    bad "$deploy has no image after the broken upgrade — expected the rollback to restore $before"
  elif [ "$after" = "$before" ]; then
    ok "rolled back — still running $after"
  else
    bad "workload image is now $after, expected the rollback to restore $before"
  fi

  info "restoring the release"
  k -n flux-system patch helmrelease event-junkie --type=json \
    -p '[{"op":"remove","path":"/spec/values/bff/image"}]' >/dev/null 2>&1 || true
  f reconcile helmrelease event-junkie >/dev/null 2>&1 || true
}

cmd_status() {
  k3d cluster list 2>/dev/null | grep -E "NAME|$CLUSTER" | sed 's/^/   /' || info "no clusters"
  kubectl config get-contexts -o name 2>/dev/null | grep -qx "$CONTEXT" || { info "context absent"; return 0; }
  k get pods -l "app.kubernetes.io/instance=$RELEASE" --no-headers 2>/dev/null | sed 's/^/   /' || info "no pods"
}

cmd_down() {
  log "Tearing down"
  if kubectl config get-contexts -o name 2>/dev/null | grep -qx "$CONTEXT"; then
    h uninstall "$RELEASE" --wait >/dev/null 2>&1 || true
  fi
  if k3d cluster delete "$CLUSTER" >/dev/null 2>&1; then
    info "cluster deleted"
  else
    info "no cluster to delete"
  fi
  if docker ps --format '{{.Names}}' | grep -qx "$PG_CONTAINER"; then
    psql_ -d postgres -c "DROP DATABASE IF EXISTS $DB;" >/dev/null 2>&1 && info "database $DB dropped"
  fi
  if [ -s "$STATE_DIR/previous-context" ]; then
    local previous; previous="$(cat "$STATE_DIR/previous-context")"
    if kubectl config use-context "$previous" >/dev/null 2>&1; then
      info "kube context restored to $previous"
    fi
    rm -f "$STATE_DIR/previous-context"
  fi
}

main() {
  case "${1:-}" in
    up)     cmd_up ;;
    verify) cmd_verify ;;
    import) shift; cmd_import "$@" ;;
    chain)  cmd_chain ;;
    images) cmd_images ;;
    test)   cmd_test ;;
    status) cmd_status ;;
    down)   cmd_down ;;
    flux-up)     cmd_flux_up ;;
    flux-verify) cmd_flux_verify ;;
    flux-trap)   cmd_flux_trap ;;
    flux-break)  cmd_flux_break ;;
    all)
      # `down` runs even when something above fails: a half-torn-down rehearsal leaves a k3d context
      # somebody later mistakes for a live cluster.
      trap cmd_down EXIT
      # READ THIS BEFORE ADDING A STEP. `set -euo pipefail` does NOT apply inside any function on the left
      # of this chain: a command in an AND-list is exempt from errexit, inherited into functions and even
      # into subshells that set -e again. Every step runs to completion on failure unless it guards itself,
      # and the chain short-circuits only on what the function RETURNS — the status of its last line, which
      # is how a release that installed nothing returned 0 (#525). Keep the chain: `up` builds a
      # precondition and must stop the run; `verify` and friends measure and are MEANT to keep going.
      cmd_up && cmd_verify && cmd_import && cmd_chain && cmd_images && cmd_test
      ;;
    # The Flux path is its own `all` and must not share a cluster: both install a release called
    # event-junkie against the same database, and two importers on one schema is the ADR-008 failure.
    flux-all)
      trap cmd_down EXIT
      cmd_flux_up && cmd_flux_verify && cmd_flux_trap && cmd_flux_break
      ;;
    ""|-h|--help) awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0" ;;
    *) die "unknown command '$1' — run '$0 --help'" ;;
  esac
  if [ "$FAILURES" -gt 0 ]; then
    printf '\n\033[31m%d assertion(s) failed\033[0m\n' "$FAILURES" >&2
    exit 1
  fi
}

main "$@"
