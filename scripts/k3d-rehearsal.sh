#!/usr/bin/env bash
#
# k3d-rehearsal.sh — run the whole stack on a local Kubernetes and prove it works end to end.
#
# Deterministic mechanics behind the /k3d-rehearsal skill, in the spirit of dev-env.sh: nobody
# re-derives the k3d, helm and kubectl incantations, and teardown is one command that always works.
#
# Per ADR-012 this is not an approximation of the production path — the chart and images that run
# here are the ones that run on Hetzner k3s, which is what makes it worth doing at all.
#
# Usage: scripts/k3d-rehearsal.sh <command>
#
#   up            Build images, create the cluster, install the chart, wait for it to converge
#   verify        Assert the ingress split from outside the cluster (routing + the negative cases)
#   import [slug] Seed one venue and source through the admin API and run a real import
#   chain         The end-to-end assertion: a scraped event comes back out of /api/events
#   test          `helm test` — the chart's own connection-test hook
#   status        What exists right now: cluster, pods, release
#   down          Uninstall, delete the cluster, drop the database, restore the kube context
#   all           up → verify → import → chain → test → down, stopping at the first failure
#
#   flux-up       Create the cluster, install the Flux controllers, apply deploy/clusters/k3d
#   flux-verify   Assert what Flux pulled: a snapshot, from GHCR, one tag, tests passed
#   flux-trap     Remove the `-0` from the semver range and watch it stop matching (#414)
#   flux-break    Break the release on purpose and watch it roll back
#   flux-all      flux-up → flux-verify → flux-trap → flux-break → down
#
# `all` and `flux-all` answer different questions and must not share a cluster. `all` installs the
# working tree's chart with images built seconds ago — "does my change work?". `flux-all` installs
# the chart already published in GHCR, through the controllers that run on Hetzner — "does the
# delivery mechanism work?".
#
# THE SAFETY RULE, because this is the one script here that talks to a Kubernetes cluster:
# every kubectl and helm call passes --context/--kube-context explicitly, and the value is a
# constant defined below. `k3d cluster create` switches the *active* context as a side effect, and a
# developer machine usually has other clusters — production ones among them — in the same
# kubeconfig. This script never relies on whatever happens to be current, and `down` puts it back.
#
# Requires: k3d, kubectl, helm, docker, yq, and a JDK + Node for the image builds.
# The opt-in airgap preload (K3D_PRELOAD_IMAGES=1) additionally wants jq, and `crane` if it can
# have it — see preload_images for why `docker save` is no longer trusted to build that tarball.

set -euo pipefail

CLUSTER=event-junkie
CONTEXT="k3d-${CLUSTER}"
RELEASE=event-junkie
CHART=deploy/charts/event-junkie
VALUES="${CHART}/values-k3d.yaml"
# Applied on top of VALUES when K3D_IMAGES=1. Off by default: it needs the compose stack's MinIO and
# two Secrets, and the plain rehearsal must keep working on a machine that has neither.
IMAGES_VALUES="${CHART}/values-k3d-images.yaml"
IMAGES="${K3D_IMAGES:-0}"
# The rehearsal gets a database of its own. Never the development one: installing the chart runs the
# importer's Flyway migrations, and pointing that at `event_junkie` would have the in-cluster
# importer competing with a local `bootRun` over one schema — with ~86 sources and thousands of
# scraped rows behind it that ADR-007 says nobody should re-scrape casually.
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
# A third outcome, for an assertion that could not be made rather than one that failed. The script
# had only pass and fail, which is why `cmd_chain` had nowhere to put "the importer persisted
# nothing, so there is nothing to measure" and called it a failure of the stack (#693). Deliberately
# not counted into FAILURES: it is not a defect, and `all` must be able to exit 0 through it.
skip() { printf '   \033[33mSKIP\033[0m %s\n' "$*"; }
die()  { printf '\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

FAILURES=0

require() {
  for t in "$@"; do command -v "$t" >/dev/null || die "$t is not installed"; done
}

# Refuses to go near a cluster this script did not create. Cheap, and the one check that matters.
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
# `flux` resolves the current kubeconfig context exactly like `helm install --dry-run` does — running
# `flux check --pre` with somebody else's context current is how that was noticed. Same rule as the
# other two: never rely on what happens to be active.
f()  { flux --context "$CONTEXT" "$@"; }
psql_() { docker exec "$PG_CONTAINER" psql -U admin "$@"; }

# Assert that an image tarball actually carries the images it claims to, and that any images named
# as extra arguments are among them.
#
# `docker save` can exit 0 having written a manifest and a config with no layer blobs at all.
# Measured here on Docker 29.7.2 with the containerd image store (#533): two of k3s's eight images
# came out as 12 KB of metadata each, `docker save` reported success, k3s's import reported success
# ("Imported images ... in 879ms"), and only `ctr -n k8s.io images check` on the node disagreed.
# The pods that could not start then failed with the same x509 error a node that cannot pull
# produces — which is the failure this whole escape hatch exists for — so the evidence pointed
# squarely at the network and the real cause stayed invisible. Three full runs went into that.
#
# The check is format-agnostic on purpose. Both writers put a `manifest.json` at the archive root
# whose `Config` and `Layers` entries are member paths — `blobs/sha256/...` from `docker save`,
# `<digest>.tar.gz` from `crane pull` — so "every path it names is in the archive" verifies either
# one. It proves presence, not integrity: a truncated blob would still pass. Absence is the failure
# that actually happens.
#
# Returns rather than dying, because the two callers want different things from a failure: a cached
# tarball gets rebuilt, a freshly fetched one is a hard stop.
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

  # Every blob being present says nothing about coverage: a tarball of seven complete images passes
  # the check above and still leaves the eighth for the node to pull. The two writers disagree about
  # the registry prefix — `docker save` strips `docker.io/` from RepoTags and `crane pull` keeps it —
  # so both sides are normalised before they are compared.
  tags="$(printf '%s\n' "$manifest" | jq -r '.[].RepoTags[]? | sub("^(index\\.)?docker\\.io/"; "")')"
  for want in "$@"; do
    printf '%s\n' "$tags" | grep -qxF "$(printf '%s' "$want" | sed -E 's#^(index\.)?docker\.io/##')" \
      || { info "$want is not in the tarball at all"; incomplete=1; }
  done

  [ "$incomplete" = 0 ]
}

# Pull k3s's own system images on the HOST and hand them to the node as an airgap tarball, which
# k3s imports at startup from /var/lib/rancher/k3s/agent/images.
#
# Off by default and deliberately opt-in: it costs a fetch-and-verify on a cold cache and nobody
# whose node can reach docker.io needs it. What it is for is a node that cannot while the host can —
# the shape a TLS-inspecting network produces, because the host trusts the interception CA and the
# k3d node's containerd does not. That failure is hard to read from the inside: the images build
# fine, `k3d cluster create` succeeds, and every pod then sits in ContainerCreating forever with an
# x509 error four `describe`s down, on the *pause sandbox* image rather than anything this project
# owns.
#
# An offline/airgap escape hatch, not a workaround for one network: it fixes any node that cannot
# pull, a genuinely offline laptop included. It does NOT install anyone's CA anywhere and must not
# grow into doing so — a shared script that injects a corporate trust root is a worse problem than
# the one it solves.
#
# WHAT A CORRECT PRELOAD LOOKS LIKE, because there are two failure modes here and #533 is what made
# them distinguishable. On success this ends with
#
#     8 images verified in build/k3d-rehearsal/airgap/k3s-airgap.tar
#
# and the word to look for is *verified*: the tarball has been read back and every layer blob its
# own manifest names is in it. Anything less fails here, loudly, naming the images — so if the
# cluster then comes up and pods still sit in ContainerCreating with an x509 error, the tarball was
# sound and the node genuinely cannot pull (#526), rather than a preload that quietly did nothing.
preload_images() {
  [ "${K3D_PRELOAD_IMAGES:-0}" = "1" ] || return 0
  # Scoped to the opt-in path rather than added to `require` at the top: the preload is the only
  # thing here that needs jq, and hard-requiring a tool for everyone to serve an escape hatch most
  # runs never touch is a poor trade.
  require jq

  local dir="$STATE_DIR/airgap" ver url tar
  tar="$dir/k3s-airgap.tar"
  mkdir -p "$dir"

  # Ask k3d which k3s it will actually run rather than pinning a version here — the two must agree,
  # and k3d's default moves with its own releases.
  ver="$(k3d version --output json | yq -p json '.k3s')"
  [ -n "$ver" ] && [ "$ver" != "null" ] || die "could not determine the k3s version k3d will use"

  if [ -f "$tar" ] && [ "$(cat "$dir/version" 2>/dev/null)" = "$ver" ]; then
    # Verified again rather than trusted. The marker is only written after a successful check, so a
    # matching marker does mean this tarball verified once — but tarballs written before #533 have a
    # marker with no check behind them, and re-reading the archive costs about a second. Failure
    # rebuilds rather than dies: the cache is keyed on the k3s version alone, which before #533 made
    # a bad tarball permanent, with `rm -rf` the only way out and nothing anywhere saying so.
    if verify_airgap_tar "$tar"; then
      info "airgap images already prepared for $ver"
      return 0
    fi
    info "the cached airgap tarball for $ver is incomplete — discarding it and fetching again"
    rm -f "$tar" "$dir/version"
  fi

  # The release publishes the canonical list; deriving it by guessing image names is how one gets
  # missed and the cluster stalls on exactly that one. `+` must be percent-encoded in the URL.
  url="https://github.com/k3s-io/k3s/releases/download/${ver//-k3s1/%2Bk3s1}/k3s-images.txt"
  log "Preloading k3s system images for $ver (K3D_PRELOAD_IMAGES=1)"
  local images=()
  while IFS= read -r image; do
    [ -n "$image" ] || continue
    images+=("$image")
  done < <(curl -sSL --fail "$url" || die "could not fetch the k3s image list from $url")

  [ "${#images[@]}" -gt 0 ] || die "the k3s image list was empty"

  # The node runs on this host's Docker, so the daemon's platform is the node's platform. `docker
  # save` picked it implicitly; `crane` has to be told.
  local plat
  plat="$(docker version --format '{{.Server.Os}}/{{.Server.Arch}}')" || plat=""
  [ -n "$plat" ] || die "could not determine the platform the k3d node will run on"

  # `crane` is preferred and `docker save` is only the fallback, which is the opposite of how this
  # started. `docker save` is the component that was writing empty images (#533) and it fetches
  # nothing anyway — it re-exports the daemon's own store, which is what was behaving unexpectedly.
  # `crane` reads the registry directly and never goes near that store. Being a static Go binary it
  # resolves TLS through the system trust store exactly as `docker pull` does, so it keeps working
  # on the interception-CA network this escape hatch exists for — which a containerised crane, with
  # its own CA bundle, would not. The fallback stays because the preload is opt-in and `docker save`
  # is fine on plenty of daemons; it is now verified either way rather than believed.
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

  # Discarded on failure so the next run refetches instead of finding a plausible-looking file, and
  # the version marker is written only past this point so a bad tarball can never become a cached one.
  verify_airgap_tar "$tar" "${images[@]}" || {
    rm -f "$tar"
    die "the airgap tarball is incomplete — the images named above are missing content, and it has been discarded. If docker save wrote it, 'brew install crane' and run again."
  }

  printf '%s' "$ver" > "$dir/version"
  info "${#images[@]} images verified in $tar"
}

# Shared by `up` and `flux-up`, which need the same cluster and the same database but install the
# chart in completely different ways — one from the working tree with locally built images, the other
# from GHCR through Flux.
create_cluster() {
  mkdir -p "$STATE_DIR"
  # Saved before k3d switches it, so `down` can put it back exactly.
  kubectl config current-context > "$STATE_DIR/previous-context" 2>/dev/null || true

  log "Creating the cluster"
  # 8080:80 publishes Traefik, which is what makes the ingress testable from the host at all.
  preload_images
  # k3s imports any tarball it finds in this directory before it starts pulling, so the mount has to
  # exist at creation time — it cannot be added to a running node.
  local airgap=()
  # `@server:0;agent:0` is not optional decoration. Without a node filter k3d warns "No node filter
  # specified" and the mount does not land where k3s looks, so the tarball is silently ignored and
  # every pod sits in ContainerCreating exactly as it did without the preload — the failure is
  # identical to the one this is meant to fix, which is the worst way for it to break.
  [ "${K3D_PRELOAD_IMAGES:-0}" = "1" ] && airgap=(--volume "$PWD/$STATE_DIR/airgap:/var/lib/rancher/k3s/agent/images@server:0;agent:0")
  # ${arr[@]+"${arr[@]}"} rather than "${arr[@]}": an empty array under `set -u` is only safe from
  # bash 4.4 on, and /bin/bash on macOS is still 3.2.
  # `|| die` is not belt-and-braces over `set -e`, for the reason `cmd_up` states at length: this
  # runs on the left of an `&&` chain in `main`, which exempts the whole function from errexit
  # recursively. Without it a failed creation was reported as success and the run carried on into the
  # CoreDNS wait, the CRD wait and the chart install against a cluster that does not exist (#692).
  # Only stdout is redirected, so k3d's own ERRO/FATA lines still reach the terminal; this message
  # adds the part k3d does not say.
  k3d cluster create "$CLUSTER" --port "8080:80@loadbalancer" --agents 1 ${airgap[@]+"${airgap[@]}"} >/dev/null \
    || die "k3d could not create the cluster — its own ERRO/FATA lines are above.
The usual cause here is something already listening on 8080, which is what the BFF binds under 'bootRun':
  lsof -nP -iTCP:8080 -sTCP:LISTEN
k3d rolls its own changes back, so there is nothing left to clean up."

  # Read into a variable and asserted rather than interpolated straight into the message. #541's
  # lesson was that a printed line is not an assertion, and this was the same shape: it printed
  # `cluster up ()` — an empty architecture from a `kubectl` that had just said "context was not
  # found" — which is what made a cluster that was never created look like one that was.
  local arch
  arch="$(k get nodes -o jsonpath='{.items[0].status.nodeInfo.architecture}' 2>/dev/null)" || arch=""
  [ -n "$arch" ] || die "the cluster reports no nodes — k3d returned 0 but context '$CONTEXT' has nothing in it"
  info "cluster up ($arch)"

  # k3d writes `host.k3d.internal` into the CoreDNS ConfigMap **after `k3d cluster create` returns**,
  # not while it runs. Measured on this machine (#541): the entry appeared **11 seconds** after create
  # returned on a bare cluster, and **7 seconds** on the rehearsal's own. Until it lands, every pod
  # resolving the database host gets `java.net.UnknownHostException: host.k3d.internal`.
  #
  # The importer is the one that shows it, and that is not luck: Flyway opens its JDBC connection
  # eagerly during context startup, so an unresolvable host fails the context and the pod
  # crash-loops. The BFF's R2DBC pool connects lazily and never notices — which is why this presents
  # as "the importer is flaky" rather than as a DNS problem.
  #
  # **Wait for the write, then restart to load it. Both halves are needed, in that order.** A
  # restart cannot load a write that has not happened: restart first and CoreDNS comes back Ready on
  # the old file, and this script reports "resolvable" seconds before it is.
  #
  # The entry lands in the ConfigMap's `NodeHosts` key — not `Corefile` — which CoreDNS reads through
  # a volume mount at /etc/coredns and watches with the *hosts* plugin's own `reload 15s`. So the
  # self-heal was kubelet's ConfigMap volume sync plus that 15s, which is long enough to produce
  # several restarts and then look like it never happened.
  #
  # It self-heals, which is worse than failing: the install still succeeds and the only evidence is a
  # restart count nobody reads. #438's rehearsal passed only because the Traefik CRD wait below
  # happened to take 15s and absorbed the gap; the run where the CRDs were ready in 0s crash-looped
  # four times.
  local dns_waited=0
  until k -n kube-system get configmap coredns -o yaml 2>/dev/null | grep -q 'host\.k3d\.internal'; do
    [ "$dns_waited" -ge 120 ] && die "k3d never wrote host.k3d.internal into the CoreDNS ConfigMap (waited ${dns_waited}s).
Every pod resolving the database host would fail. Check 'kubectl -n kube-system get configmap coredns -o yaml'."
    sleep 1
    dns_waited=$((dns_waited + 1))
  done
  info "host.k3d.internal written to the CoreDNS ConfigMap after ${dns_waited}s"

  # Now the restart means something: a new pod mounts the ConfigMap as it is, so it comes up already
  # holding the entry rather than waiting on the volume sync and the plugin's own reload.
  # The `rollout status` below carries a `|| die` and this did not, so a restart that never happened
  # was waited on rather than reported (#692, same class as the create above).
  k -n kube-system rollout restart deployment coredns >/dev/null \
    || die "could not restart CoreDNS — it would come back on the config without host.k3d.internal"
  k -n kube-system rollout status deployment coredns --timeout=120s >/dev/null \
    || die "CoreDNS did not roll out — every pod resolving host.k3d.internal will fail"
  info "CoreDNS restarted onto it"

  # k3s installs its bundled Traefik through a HelmChart CR that the helm-controller reconciles
  # asynchronously, so `k3d cluster create` returns BEFORE `traefik.io/v1alpha1` is a kind the API
  # server knows. Install inside that window and Helm fails the whole release with
  # "no matches for kind Middleware" — not just the middleware: nothing is installed at all.
  #
  # Nothing hit this until #286, and the reason is worth keeping. The chart renders exactly two
  # Traefik CRD objects: the redirect Middleware, gated on `redirectHosts`, and the noindex one,
  # gated on `ingress.noindex`. k3d had `redirectHosts: []` and no `noindex`, so it had never
  # rendered a Traefik kind at all and never needed the CRDs to exist. Turning noindex on for the
  # rehearsal is what made this reachable — the race was always there.
  #
  # Waiting on the CRD rather than on the Job because the CRD is the thing Helm actually needs, and
  # `kubectl wait` cannot wait for a resource that does not exist yet — hence the poll.
  log "Waiting for Traefik's CRDs — the chart renders Middleware objects and Helm resolves kinds up front"
  local waited=0
  until k get crd middlewares.traefik.io >/dev/null 2>&1; do
    [ "$waited" -ge 180 ] && die "Traefik CRDs never appeared (waited ${waited}s).
A pod stuck in ContainerCreating on an image pull is the usual cause — check
'kubectl -n kube-system get pods' and whether this machine can reach docker.io."
    sleep 5
    waited=$((waited + 5))
  done
  # Polled rather than waited on with `kubectl wait`, and that is not a style choice — **both** of
  # its `--for` forms fail instantly here rather than waiting (#696).
  #
  # `--for=condition=established` is the form to avoid. The poll above returns the moment the
  # *object* exists, which can be before the API server has populated the status
  # subresource, and at that instant `.status.conditions` is present and explicitly **null**. On that
  # shape `--for=condition` exits 1 immediately with
  #
  #     error: .status.conditions accessor error: <nil> is of the type <nil>, expected []interface{}
  #
  # so the 60s were never spent. Measured on kubectl 1.36.4: an explicit `conditions: null` fails in
  # ~1s, while a status that merely *lacks* conditions waits correctly — which is why this is
  # intermittent and why four rehearsals the same day printed "ready after 0s" without noticing.
  #
  # `--for='jsonpath={...}=True'` is the usual advice for that bug and **does not work either**: on
  # the same object it exits 1 in 0s with `<nil> is not array or slice and cannot be filtered`. Both
  # were measured against a resource patched to `status: {conditions: null}` on purpose; do not swap
  # this back for either of them.
  #
  # `kubectl get -o jsonpath` fails the same way on that shape, but here it is harmless: a failed
  # read is empty, empty is not True, and the loop simply goes round again. Errors go to /dev/null so
  # a transient one cannot leave a misleading explanation on screen next to the die below.
  local jsonpath='{.status.conditions[?(@.type=="Established")].status}' established=0
  until [ "$(k get crd middlewares.traefik.io -o jsonpath="$jsonpath" 2>/dev/null)" = "True" ]; do
    # The elapsed total is in the message, so this can never again describe a wait that did not happen.
    [ "$established" -ge 60 ] && die "the Middleware CRD exists but was still not Established ${established}s later (${waited}s into the wait).
'kubectl get crd middlewares.traefik.io -o yaml' shows its status; a null .status.conditions means the API server never finished registering it."
    sleep 1
    established=$((established + 1))
  done
  # Both stages, because the CRD is not ready until the second one says so — reporting only the
  # existence poll would print "ready after 0s" for a wait that spent seconds becoming Established.
  info "Traefik CRDs ready after $((waited + established))s"
}

# Creates the rehearsal's own empty database and the credentials Secret. `namespace` decides where
# the Secret lands: the helm path installs into `default`, the Flux path into the release's target
# namespace, and a Secret in the wrong namespace fails the release for a reason that looks like Flux.
prepare_database() {
  local namespace="${1:-default}"
  log "Database and secret"
  docker compose up -d >/dev/null 2>&1
  # `docker compose up -d` returns when the container is *started*, not when PostgreSQL is accepting
  # connections — and the gap is long enough that the very next psql call fails with a socket error
  # that reads like a misconfiguration. dev-env.sh has the same wait for the same reason.
  local i
  for i in $(seq 1 30); do
    if docker exec "$PG_CONTAINER" pg_isready -U admin -d postgres >/dev/null 2>&1; then break; fi
    [ "$i" = 30 ] && die "PostgreSQL did not become ready within 30s"
    sleep 1
  done
  psql_ -d postgres -c "DROP DATABASE IF EXISTS $DB;" -c "CREATE DATABASE $DB OWNER admin;" >/dev/null
  k create namespace "$namespace" >/dev/null 2>&1 || true
  k -n "$namespace" create secret generic events-db \
    --from-literal=username=admin --from-literal=password=admin >/dev/null
  info "database $DB created empty; secret events-db created in namespace $namespace"

  [ "$IMAGES" = 1 ] || return 0
  # The two Secrets the image path needs. Both are created out of band in every real environment
  # (SECRETS.md), which is exactly why the chart has no path that invents them and why they have to
  # exist before the install rather than after it — `secretKeyRef` is not optional, so values that
  # land ahead of a Secret leave the pod in CreateContainerConfigError.
  k -n "$namespace" create secret generic event-junkie-images \
    --from-literal=IMAGE_STORAGE_ACCESS_KEY=minioadmin \
    --from-literal=IMAGE_STORAGE_SECRET_KEY=minioadmin >/dev/null
  # A shared secret between two containers rather than a credential to anything, so a value fixed
  # here costs nothing. It is still 64 hex characters, because imgproxy parses it as hex and a
  # shorter one would fail in a way this rehearsal is not trying to discover.
  local key salt
  key="$(printf '61%.0s' $(seq 1 32))"
  salt="$(printf '62%.0s' $(seq 1 32))"
  k -n "$namespace" create secret generic event-junkie-imgproxy \
    --from-literal="IMGPROXY_KEY=$key" --from-literal="IMGPROXY_SALT=$salt" >/dev/null
  info "secrets event-junkie-images and event-junkie-imgproxy created"
}

cmd_up() {
  require k3d kubectl helm docker yq

  # EVERY FALLIBLE STEP BELOW CARRIES AN EXPLICIT `|| die`, AND THAT IS NOT BELT-AND-BRACES OVER
  # `set -euo pipefail` — it is the only thing standing in for it here. `main` runs this function on
  # the left of an `&&` chain, and a command in an AND-list is exempt from errexit for the whole
  # function, recursively. So without these the build could fail, the cluster could fail, the release
  # could fail to install, and this function would still run to its last line and return that line's
  # status. It did exactly that (#525): a release that installed nothing was reported as success and
  # the run carried on into four assertions that measured an empty cluster.
  #
  # `up` builds a precondition, so it fails fast. `verify` measures, so it accumulates into FAILURES
  # and reports at the end — those are different jobs and deliberately behave differently.
  log "Building the three images"
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

  create_cluster
  k3d image import -c "$CLUSTER" \
    localhost/event-junkie/bff:dev localhost/event-junkie/importer:dev localhost/event-junkie/frontend:dev >/dev/null \
    || die "could not import the images into the cluster — every pod would then try to pull them from a registry that does not have them"
  info "images imported"

  prepare_database default

  # THE ONE THAT MATTERS. Helm resolves every kind up front, so an unknown one fails the entire
  # release rather than a single object — nothing is installed at all, and the message says so in a
  # sentence that scrolls away behind whatever ran next.
  log "Installing the chart"
  local values_args=(--values "$VALUES")
  if [ "$IMAGES" = 1 ]; then
    values_args+=(--values "$IMAGES_VALUES")
    info "image caching enabled — imgproxy sidecar, MinIO on the host, serving on"
  fi
  h install "$RELEASE" "$CHART" "${values_args[@]}" --wait --timeout 5m >/dev/null \
    || die "the release did not install — nothing below this point would be measuring the chart"
  # Informational, and deliberately before the assertion below: it succeeds whether or not anything
  # is running, so its status must never be what this function returns. Every step above exits on
  # failure, so by the time control reaches here the install has genuinely succeeded.
  k get pods -l "app.kubernetes.io/instance=$RELEASE" --no-headers | sed 's/^/   /'

  # `--wait` establishes Ready, and Ready is not the bar. /k3d-rehearsal asks for Ready **and no
  # restarts**, because a pod that recovered after crashing is a different result from one that
  # started — and until #544 nothing checked it. That is how #541 survived two rehearsals: the
  # importer restarted four times on a DNS race and both runs were reported clean, because the only
  # evidence was a column in a table printed for a human to read.
  #
  # `bad` rather than `die`, deliberately. Every other step in this function is a precondition and
  # fails fast; a restart count is a *measurement*. The stack is up and the rest of the rehearsal is
  # still worth running, so this accumulates into FAILURES and fails the run at the end — the same
  # contract cmd_verify has.
  #
  # Summed across containers rather than read from the first, so a workload that gains a sidecar does
  # not quietly stop being covered.
  local restarted
  restarted="$(k get pods -l "app.kubernetes.io/instance=$RELEASE" \
    -o jsonpath='{range .items[*]}{.metadata.name}{" "}{.status.containerStatuses[*].restartCount}{"\n"}{end}' \
    | awk '{ s = 0; for (i = 2; i <= NF; i++) s += $i; if (s > 0) print $1, s }')"
  if [ -z "$restarted" ]; then
    ok "all pods Ready with no restarts"
  else
    # A here-string, NOT a pipe. `while read` on the right of a pipe runs in a subshell, and every
    # FAILURES increment inside it would be discarded when that subshell exits — an assertion that
    # prints FAIL and does not fail the run.
    local pod count
    while read -r pod count; do
      bad "$pod restarted ${count}x before becoming Ready — Ready is not the bar (#544). 'kubectl logs --previous' will say why"
    done <<< "$restarted"
  fi
}

# Every negative assertion here checks the CONTENT TYPE, not the status code, and that is the whole
# point of the function. nginx serves the SPA for any unmatched path, so `/actuator/health` through
# the ingress returns 200 — a status-only test passes for entirely the wrong reason and would keep
# passing if actuator were genuinely exposed.
cmd_verify() {
  guard_context
  local code type
  probe() { # probe <path> -> sets code/type
    code="$(curl -s -o /dev/null -w '%{http_code}' -H "$HOST_HEADER" --max-time 10 "$BASE$1")"
    type="$(curl -s -o /dev/null -w '%{content_type}' -H "$HOST_HEADER" --max-time 10 "$BASE$1")"
  }

  # The header below promises the negatives prove nothing if the positives fail, and until #544
  # nothing enforced it. The verdict was never wrong — `FAILURES` still fails the run — but the
  # transcript carried green ticks the script itself calls meaningless, and a transcript is what
  # somebody reads at 23:00 to decide whether to ship.
  #
  # The shape of the false green: with the ingress misrouting, `/actuator/health` answers Traefik's
  # own HTML error page (`ok`, "it is the SPA fallback") and `/api/admin/sources` answers 404 because
  # nothing is routed at all (`ok`, "it does not reach the importer"). Both are true statements about
  # a broken cluster and neither says anything about the security property they are named for.
  #
  # `unproven` deliberately does NOT touch FAILURES: the positive that failed already counted the
  # outage, and counting it again would make the summary claim three things broke when one did.
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

  # The one thing no render assertion can establish (#286). The chart mounts a ConfigMap over the
  # `robots.txt` and `sitemap.xml` that the frontend build bakes into the image, using `subPath` —
  # and a `subPath` naming a key the ConfigMap does not have mounts an EMPTY DIRECTORY over the file
  # rather than failing. The pod stays Ready either way and the manifest looks identical, so the
  # only evidence that the override reached nginx is the bytes it hands back.
  #
  # Which is why these assert on the body and not on the status: both files return 200 whichever
  # copy is served. The two are told apart by content — the image's says `Allow: /` and carries a
  # `Sitemap:` line naming production; the mounted one says `Disallow: /` and carries none.
  # EVERY ASSERTION BELOW IS PHRASED AS AN ABSENCE, AND AN ABSENCE IS TRUE OF NOTHING AT ALL.
  # The first version of this block reported "names no sitemap" and "lists nothing" as passes during
  # a run where the release had failed to install and the ingress was answering 000 — both were
  # trivially true of an empty string. So each fetch establishes that it got a real response first,
  # and only then asserts on what is missing from it. An assertion that cannot fail is worse than no
  # assertion, because it is counted.
  # These are NOT gated on `positives`, and the exemption is deliberate rather than an oversight:
  # `fetched` below establishes its own precondition — a 200 with a non-empty body — which is a
  # stronger statement than "the ingress routes /", not a weaker one. Gating them as well would turn
  # real evidence about the mount into `unproven` on the strength of an unrelated failure.
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
    # The image's copy says `Allow: /` and carries a `Sitemap:` line naming production; the mounted
    # one says `Disallow: /` and carries none. That is how the two are told apart — both are 200.
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
  # One import of one venue, once — ADR-007, whichever venue that is.
  #
  # AMT by default: a small club, and the one the rehearsal has always used. Under K3D_IMAGES it is
  # Cassiopeia instead, because AMT frequently publishes nothing upcoming — which is a fine result
  # for the chain (#693 says so) and useless for the image path, since an event that is never
  # persisted has no `image_url` to cache. The image rehearsal needs a venue that reliably has both.
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

  # A fixed sleep is the same class of defect as #541 — a timer standing in for a poll — at much
  # lower stakes, because this one fails loudly through `die "venue POST failed"` rather than passing
  # wrongly. It is still four seconds that are only ever "usually enough", and the reader of the
  # resulting failure has no way to tell a slow port-forward from a broken importer.
  #
  # Any HTTP status at all is the evidence wanted here: it proves the tunnel is open and something is
  # listening behind it. curl reports 000 for a refused connection or a timeout, which is the only
  # value that means "not yet".
  local waited=0
  until [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time 2 "$api/venues")" != 000 ]; do
    kill -0 "$pf" 2>/dev/null || die "the port-forward to ${RELEASE}-importer died after ${waited}s — 'kubectl port-forward' would have said why on stderr, which this discards"
    [ "$waited" -ge 60 ] && die "the importer never answered on :18081 after ${waited}s — the port-forward is up but nothing is serving behind it"
    sleep 1
    waited=$((waited + 1))
  done
  info "importer answering on :18081 after ${waited}s"

  # The address is the venue's own and is not what is under test; the slug the importer derives from
  # `name` is, because every later call addresses the source by it.
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
# here. Every other check in this script can pass with the pieces working only in isolation.
cmd_chain() {
  guard_context
  log "The chain: a scraped event coming back out through the ingress"
  local sources rows reported titles
  # Read from the database rather than the admin API: `chain` owns no port-forward, and going
  # through psql keeps it working standalone as well as inside `all`. Summed rather than looked up by
  # slug so it does not need `cmd_import`'s venue argument threaded through.
  sources="$(psql_ -d "$DB" -tAc 'select count(*) from events.event_source' | tr -d ' ')"
  rows="$(psql_ -d "$DB" -tAc 'select count(*) from events.event' | tr -d ' ')"
  reported="$(psql_ -d "$DB" -tAc 'select coalesce(sum(last_event_count), 0) from events.event_source' | tr -d ' ')"

  # Three outcomes, and only one of them is a defect in the stack. Before #693 all three printed
  # "no rows … run 'import' first", which was wrong in two of them: inside `all` the import had run
  # seconds earlier and returned SUCCESS, so the remedy named a step that had already succeeded.
  if [ "${sources:-0}" -eq 0 ]; then
    # The one case where the old message was right: nothing has been seeded in this database.
    bad "no event sources in $DB — run '$0 import' first"
    return 1
  elif [ "${rows:-0}" -gt 0 ]; then
    ok "$rows event rows written by the in-cluster importer"
  elif [ "${reported:-0}" -gt 0 ]; then
    # The importer says it persisted events and the database has none. This is the chain breaking
    # between the importer and PostgreSQL, and it is the failure this assertion exists to catch.
    bad "the importer reported $reported event(s) persisted and $DB has none — the chain is broken between the importer and PostgreSQL"
    return 1
  else
    # Nothing was persisted, so there is nothing downstream to measure. `EventUpsertService`
    # partitions on `!eventDate.isBefore(today)` and drops the past side, so a venue whose next month
    # is unpublished produces exactly this: a SUCCESS that writes nothing. Not a failure of the
    # stack — and deliberately not an `ok` either, because a scraper that has genuinely stopped
    # finding anything lands here too and this script cannot tell the two apart. `last_event_count`
    # counts what survived the drop; the number dropped exists only in the importer's log.
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

# The image path, end to end: the importer fetches a venue's poster, stores it in MinIO, asks the
# imgproxy sidecar for each width and format, and the BFF serves one back through Traefik.
#
# **This is the step the three staging defects would each have failed** — a sidecar that would not
# start, a sidecar nothing called, and an invariant that had never rendered a second container.
# `helm template` passed for all three, which is the whole argument for running the thing.
cmd_images() {
  guard_context
  if [ "$IMAGES" != 1 ]; then
    skip "image caching not enabled — re-run with K3D_IMAGES=1"
    return 0
  fi
  log "The image path: fetch, store, derive, serve"

  # The importer's pass is on a five-minute tick and the import above only just seeded the events it
  # reads, so the first pass with anything to do is up to that far away. Polled rather than slept:
  # a fixed wait is either wrong or wasteful, and this prints what it is waiting for.
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

  # Every format, not just the count. A run that produced only JPEG means imgproxy answered and the
  # formats this design exists for did not arrive.
  local formats
  formats="$(psql_ -d "$DB" -tAc "select string_agg(distinct format, ',' order by format) from events.cached_image_variant" | tr -d ' ')"
  case "$formats" in
    *avif*) ok "formats generated: $formats" ;;
    *) bad "only $formats generated — imgproxy answered but produced no AVIF, which is what ADR-020 chose it for" ;;
  esac

  # And the other end: one of those objects, served from our own origin through a real Traefik.
  local hash width type
  hash="$(psql_ -d "$DB" -tAc "select c.content_hash from events.cached_image c join events.cached_image_variant v on v.cached_image_id = c.id where v.format = 'jpg' limit 1" | tr -d ' ')"
  width="$(psql_ -d "$DB" -tAc "select v.width from events.cached_image c join events.cached_image_variant v on v.cached_image_id = c.id where v.format = 'jpg' and c.content_hash = '$hash' order by v.width limit 1" | tr -d ' ')"
  type="$(curl -s -o /dev/null -w '%{content_type}' -H "$HOST_HEADER" "$BASE/api/images/$hash/$width.jpg")"
  if [ "$type" = "image/jpeg" ]; then
    ok "a derivative served through the ingress as image/jpeg"
  else
    bad "GET /api/images/$hash/$width.jpg returned '$type' rather than image/jpeg — the serving path is broken"
  fi

  # The substitution, which is what actually stops the browser contacting the venue. A URL still
  # pointing at a venue here means the BFF found no derivative and reported the source instead.
  local served
  served="$(curl -s -H "$HOST_HEADER" "$BASE/api/events?size=20" | yq -p json '[.content[] | select(.imageUrl == "/api/images/*")] | length')"
  if [ "${served:-0}" -gt 0 ]; then
    ok "$served event(s) served an imageUrl on our own origin"
  else
    bad "no event carried an imageUrl under /api/images — the BFF is still handing out venue URLs"
  fi
}

# The output is piped, so `pipefail` carries helm's exit status out of this function and errexit
# stops the run at the `&&` in `main` — correctly, and until #544 completely silently: the reader saw
# the previous step's `ok`, then teardown, with no line naming the step that ended it. Capture first,
# then judge, so the transcript says which step failed and with what.
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
# A different question from `up`'s. That one installs the working tree's chart with images built
# thirty seconds ago and answers "does my change work?". This installs the chart that is *published*
# in GHCR, with the images that chart names, through the same controllers that will run on Hetzner —
# and answers "does the delivery mechanism work?". Neither substitutes for the other.
#
# Deliberately NOT `flux bootstrap`: that commits Flux's manifests to this repository and creates a
# deploy key on it, which is an outward-facing side effect in exchange for exercising git-sync — the
# one part of Flux that genuinely needs a real cluster anyway. `flux install` gives the controllers;
# the CRs are applied straight from the working tree.
cmd_flux_up() {
  require k3d kubectl flux docker yq
  create_cluster
  prepare_database "$FLUX_NS"

  # EVERY FALLIBLE STEP BELOW CARRIES AN EXPLICIT `|| die`, FOR THE REASON `cmd_up` STATES AT LENGTH:
  # this function runs on the left of an `&&` chain in `main`, and a command in an AND-list is exempt
  # from errexit for the whole function, recursively. #525 applied that lesson to the helm half only
  # (#544), so `f install` and `k apply` could both fail here and the run would carry on to report
  # "OCIRepository never became Ready" — sending the reader to investigate a registry or semver-range
  # problem that does not exist.
  log "Installing the Flux controllers"
  f install >/dev/null \
    || die "flux install failed — there are no controllers, and every failure after this point would be a symptom of that rather than of the chart"
  # A count printed as a fact is how #533 and #541 both hid: `0 controllers installed` prints as
  # calmly as `6`. Naming the two this rehearsal cannot work without beats counting them — a merely
  # non-zero count still passes with the wrong set installed, and what `flux install` deploys moves
  # between versions, so an exact number would be brittle for no gain.
  for c in source-controller helm-controller; do
    k -n flux-system get "deploy/$c" >/dev/null 2>&1 \
      || die "flux install reported success without a $c — the OCIRepository/HelmRelease below would never reconcile, and would say nothing about why"
  done
  info "$(k -n flux-system get deploy -o name | wc -l | tr -d ' ') controllers installed, including source-controller and helm-controller"

  log "Applying deploy/clusters/k3d"
  k apply -k "$FLUX_DIR" >/dev/null \
    || die "kubectl apply -k $FLUX_DIR failed — the OCIRepository and HelmRelease were never created, so waiting on them below would time out on resources that do not exist"
  # Bounded, and separately, so a failure names which half broke. A source that never becomes Ready
  # is a registry or a semver-range problem; a release that never becomes Ready is a chart or a
  # values problem, and they need entirely different investigations.
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

  local revision
  revision="$(k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.artifact.revision}')"
  # The whole point of the `-0` in the semver range. A snapshot is a SemVer prerelease, and a range
  # without a prerelease comparator skips it silently — so resolving one is the evidence, and
  # `flux-trap` below shows the failure mode by removing it.
  case "$revision" in
    *snapshot*) ok "resolved a snapshot: $revision" ;;
    "")         bad "no artifact resolved at all" ;;
    *)          bad "resolved '$revision', which is not a snapshot — is the -0 missing from the range?" ;;
  esac

  local images
  images="$(k -n "$FLUX_NS" get deploy -o jsonpath='{range .items[*]}{.spec.template.spec.containers[*].image}{"\n"}{end}')"
  if printf '%s' "$images" | grep -q '^ghcr.io/enorm-labs/event-junkie/'; then
    ok "workloads run images pulled from GHCR, not side-loaded"
    printf '%s\n' "$images" | sed 's/^/     /'
  else
    bad "expected ghcr.io images, got: $images"
  fi

  # Every image tag must equal the chart's appVersion, which is what #264's fallback promises. If
  # that fallback ever breaks, this is where it shows up as three tags that disagree.
  local distinct
  distinct="$(printf '%s\n' "$images" | sed 's/.*://' | sort -u | wc -l | tr -d ' ')"
  if [ "$distinct" = 1 ]; then
    ok "all three images carry one tag — the appVersion fallback holds"
  else
    bad "$distinct distinct image tags; the chart and the images have drifted"
  fi

  # Flux runs the chart's own `helm test` hook as part of reconciliation and records the result as a
  # condition. This is the in-cluster smoke test that replaces the external one CI cannot run (§4a).
  if [ "$(k -n flux-system get helmrelease event-junkie -o jsonpath='{.status.conditions[?(@.type=="TestSuccess")].status}')" = "True" ]; then
    ok "helm test ran in-cluster and passed"
  else
    bad "TestSuccess is not True — the chart's test hook did not pass"
  fi
}

# Removes the `-0` and watches the range stop matching. The trap this issue exists to avoid is
# silent, so the only way to trust the range is to see both states — matching, and not.
cmd_flux_trap() {
  guard_context
  log "The prerelease trap, observed rather than trusted"
  k -n flux-system patch ocirepository event-junkie --type=merge \
    -p '{"spec":{"ref":{"semver":">=0.0.0"}}}' >/dev/null
  f reconcile source oci event-junkie >/dev/null 2>&1 || true
  sleep 5
  local ready message
  ready="$(k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}')"
  message="$(k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.conditions[?(@.type=="Ready")].message}')"
  if [ "$ready" = "False" ]; then
    ok "without the -0 the range matches nothing: $message"
  else
    bad "expected the range to stop matching, but Ready=$ready ($message)"
  fi

  info "restoring the range"
  k -n flux-system patch ocirepository event-junkie --type=merge \
    -p '{"spec":{"ref":{"semver":">=0.0.0-0"}}}' >/dev/null
  f reconcile source oci event-junkie >/dev/null 2>&1 || true
  # `wait && ok` with no else was a silent stop (#544): a failed restore returned non-zero, `main`'s
  # AND-chain halted, and the reader saw the previous step's `ok` followed by teardown with nothing
  # naming the step that ended the run. It also leaves the cluster in the broken state this function
  # created on purpose, which the next step would then measure.
  if k -n flux-system wait ocirepository/event-junkie --for=condition=Ready --timeout=2m >/dev/null 2>&1; then
    ok "range restored, artifact resolves again"
  else
    bad "the range was NOT restored — the OCIRepository is still not Ready, and this function left it that way"
    k -n flux-system get ocirepository event-junkie -o jsonpath='{.status.conditions[*].message}' | sed 's/^/     /'
    return 1
  fi
}

# Breaks a release on purpose and watches the rollback. This is the single most valuable thing to see
# outside an incident, and #263's rehearsal had no equivalent — it could prove the stack came up, but
# never that a bad deploy is survivable.
cmd_flux_break() {
  guard_context
  log "Breaking the release on purpose"
  # The Deployment is NAMED, not taken as `.items[0]` (#544). Sorted order made that the bff by
  # accident — `…-bff` sorts before `…-frontend` and `…-importer` — and the patch below breaks the
  # *bff* tag specifically. Anything sorting earlier, or a rename, would leave this comparing an
  # image nobody touched, which passes every time and proves nothing.
  local deploy="deploy/${RELEASE}-bff"
  local before
  before="$(k -n "$FLUX_NS" get "$deploy" -o jsonpath='{.spec.template.spec.containers[0].image}' 2>/dev/null)"
  # An equality of two empty strings is true. Establish there is a real image to compare before
  # comparing it, or the rollback assertion below passes on an empty namespace and a failed kubectl
  # alike — the trap cmd_verify spells out in capitals and this function had never learned.
  if [ -z "$before" ]; then
    bad "$deploy has no image to read — there is nothing for the rollback assertion to be about"
    return 1
  fi
  info "currently running $before"

  # `timeout` and `retries: 0` are what keep this to about a minute. Left at the file's own values a
  # failing upgrade would take 5m per attempt plus a retry, and a rehearsal nobody waits for is a
  # rehearsal nobody runs.
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

  # The property that actually matters: the site kept serving the last good version throughout.
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
      # `down` runs even when something above fails, because a half-torn-down rehearsal leaves a
      # k3d context behind that somebody later mistakes for a live cluster.
      trap cmd_down EXIT
      # READ THIS BEFORE ADDING A STEP. `set -euo pipefail` at the top of this file does NOT apply
      # inside any function on the left of this chain: a command in an AND-list is exempt from
      # errexit, and the exemption is inherited into functions and even into subshells that set -e
      # again. So every step here runs to completion on failure unless it guards itself, and the
      # chain only short-circuits on what the function happens to RETURN — which is the status of
      # its last line.
      #
      # That combination is what #525 was: cmd_up's last line was an informational `kubectl get
      # pods`, so a release that installed nothing returned 0 and cmd_verify then measured an empty
      # cluster. cmd_up now carries an explicit `|| die` on every fallible step.
      #
      # Keep the chain rather than plain sequencing, because the two kinds of step are not alike:
      # `up` builds a precondition and must stop the run, while `verify` and friends measure and are
      # MEANT to keep going and report at the end through FAILURES. Real errexit here would abort
      # `verify` on its first failed curl, which is the opposite of what it is for.
      cmd_up && cmd_verify && cmd_import && cmd_chain && cmd_images && cmd_test
      ;;
    # The Flux path is its own `all`, and must not share a cluster with the one above: both install a
    # release called event-junkie against the same database, so running them together would put two
    # importers on one schema — the exact ADR-008 failure the chart pins replicas to prevent.
    flux-all)
      trap cmd_down EXIT
      cmd_flux_up && cmd_flux_verify && cmd_flux_trap && cmd_flux_break
      ;;
    ""|-h|--help) sed -n '2,30p' "$0" | sed 's/^# \{0,1\}//' ;;
    *) die "unknown command '$1' — run '$0 --help'" ;;
  esac
  if [ "$FAILURES" -gt 0 ]; then
    printf '\n\033[31m%d assertion(s) failed\033[0m\n' "$FAILURES" >&2
    exit 1
  fi
}

main "$@"
