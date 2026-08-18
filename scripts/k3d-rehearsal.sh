#!/usr/bin/env bash
#
# k3d-rehearsal.sh — run the whole stack on a local Kubernetes and prove it works end to end.
#
# Deterministic mechanics behind the /k3d-rehearsal skill, in the same spirit as dev-env.sh: the
# sequence is scripted so nobody re-derives k3d, helm and kubectl incantations, and so the teardown
# is one command that always works rather than four that are easy to half-finish.
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

set -euo pipefail

CLUSTER=event-junkie
CONTEXT="k3d-${CLUSTER}"
RELEASE=event-junkie
CHART=deploy/charts/event-junkie
VALUES="${CHART}/values-k3d.yaml"
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

# Shared by `up` and `flux-up`, which need the same cluster and the same database but install the
# chart in completely different ways — one from the working tree with locally built images, the other
# from GHCR through Flux.
# Pull k3s's own system images on the HOST and hand them to the node as an airgap tarball, which
# k3s imports at startup from /var/lib/rancher/k3s/agent/images.
#
# Off by default and deliberately opt-in: it costs a pull-and-save on a cold cache and nobody whose
# node can reach docker.io needs it. What it is for is a node that cannot, while the host can — the
# shape a TLS-inspecting network produces, because the host trusts the interception CA and the k3d
# node's containerd does not. That failure is genuinely hard to read from the inside: the images
# build fine, `k3d cluster create` succeeds, and every pod then sits in ContainerCreating forever
# with an x509 error four `describe`s down, on the *pause sandbox* image rather than on anything
# this project owns.
#
# This is an offline/airgap escape hatch, not a workaround for one network. It fixes any node that
# cannot pull, including a genuinely offline laptop. It does NOT install anyone's CA anywhere, and
# it must not grow into doing so — a shared script that injects a corporate trust root is a worse
# problem than the one it solves.
preload_images() {
  [ "${K3D_PRELOAD_IMAGES:-0}" = "1" ] || return 0

  local dir="$STATE_DIR/airgap" ver url tar
  tar="$dir/k3s-airgap.tar"
  mkdir -p "$dir"

  # Ask k3d which k3s it will actually run rather than pinning a version here — the two must agree,
  # and k3d's default moves with its own releases.
  ver="$(k3d version --output json | yq -p json '.k3s')"
  [ -n "$ver" ] && [ "$ver" != "null" ] || die "could not determine the k3s version k3d will use"

  if [ -f "$tar" ] && [ "$(cat "$dir/version" 2>/dev/null)" = "$ver" ]; then
    info "airgap images already prepared for $ver"
    return 0
  fi

  # The release publishes the canonical list; deriving it by guessing image names is how one gets
  # missed and the cluster stalls on exactly that one. `+` must be percent-encoded in the URL.
  url="https://github.com/k3s-io/k3s/releases/download/${ver//-k3s1/%2Bk3s1}/k3s-images.txt"
  log "Preloading k3s system images for $ver (K3D_PRELOAD_IMAGES=1)"
  local images=()
  while IFS= read -r image; do
    [ -n "$image" ] || continue
    images+=("$image")
    docker pull -q "$image" >/dev/null || die "could not pull $image on the host either — this is not the node's trust store, it is the network"
  done < <(curl -sSL --fail "$url" || die "could not fetch the k3s image list from $url")

  [ "${#images[@]}" -gt 0 ] || die "the k3s image list was empty"
  docker save "${images[@]}" -o "$tar" || die "could not save the airgap tarball"
  printf '%s' "$ver" > "$dir/version"
  info "${#images[@]} images saved to $tar"
}

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
  k3d cluster create "$CLUSTER" --port "8080:80@loadbalancer" --agents 1 ${airgap[@]+"${airgap[@]}"} >/dev/null
  info "cluster up ($(k get nodes -o jsonpath='{.items[0].status.nodeInfo.architecture}'))"

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
  # **Wait for the write, then restart to load it. Both halves are needed.** This used to restart
  # first and wait for the rollout, on the belief that k3d had already written the entry and that the
  # Corefile `reload` plugin was what lagged. Both were wrong: a restart cannot load a write that has
  # not happened, so CoreDNS came back Ready on the old file and this script printed "resolvable"
  # eleven seconds before it was.
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
  k -n kube-system rollout restart deployment coredns >/dev/null
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
  k wait --for=condition=established --timeout=60s crd/middlewares.traefik.io >/dev/null \
    || die "the Middleware CRD exists but never became Established"
  info "Traefik CRDs ready after ${waited}s"
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
  h install "$RELEASE" "$CHART" --values "$VALUES" --wait --timeout 5m >/dev/null \
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

  log "Positive routing — these must pass, or the negatives below prove nothing"
  probe /
  if [ "$code" = 200 ] && [ "${type#text/html}" != "$type" ]; then
    ok "/ serves the SPA"
  else
    bad "/ -> $code $type"
  fi

  probe /api/events
  if [ "$code" = 200 ] && [ "${type#application/json}" != "$type" ]; then
    ok "/api/events reaches the BFF"
  else
    bad "/api/events -> $code $type"
  fi

  log "Negative routing — the security properties"
  probe /actuator/health
  if [ "${type#text/html}" != "$type" ]; then
    ok "/actuator/health is the SPA fallback, not actuator"
  else
    bad "/actuator/health returned $type — actuator may be exposed"
  fi

  probe /api/admin/sources
  if [ "$code" = 404 ]; then
    ok "/api/admin/** does not reach the importer (BFF 404)"
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
  local slug="${1:-amt}"
  log "Seeding one source and running a real import (one small venue, once — ADR-007)"
  k port-forward "svc/${RELEASE}-importer" 18081:8081 >/dev/null 2>&1 &
  local pf=$!
  # shellcheck disable=SC2064  # expand $pf now
  trap "kill $pf 2>/dev/null || true" RETURN
  sleep 4
  local api=localhost:18081/api/admin

  local vid
  vid="$(curl -sS -X POST "$api/venues" -H 'Content-Type: application/json' -d '{
    "name":"AMT","address":"Dircksenstr. 114","city":"Berlin","postalCode":"10178","district":"mitte",
    "latitude":52.5137,"longitude":13.418,"websiteUrl":"https://www.club-amt.berlin",
    "description":"Small club under the S-Bahn arches at Alexanderplatz."}' | yq -p json '.id')"
  if [ -z "$vid" ] || [ "$vid" = "null" ]; then die "venue POST failed"; fi
  info "venue id $vid"

  curl -sS -X POST "$api/event-sources" -H 'Content-Type: application/json' -d "{
    \"venueId\":$vid,\"name\":\"AMT\",\"url\":\"https://www.club-amt.berlin/events\",
    \"sourceType\":\"AMT\",\"enabled\":true,\"importIntervalMinutes\":1440,\"maxRetries\":3}" >/dev/null
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
  local rows titles
  rows="$(psql_ -d "$DB" -tAc 'select count(*) from events.event' | tr -d ' ')"
  if [ "${rows:-0}" -gt 0 ]; then
    ok "$rows event rows written by the in-cluster importer"
  else
    bad "no rows in $DB — run '$0 import' first"
    return 1
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

cmd_test() { guard_context; log "helm test"; h test "$RELEASE" --timeout 3m | tail -4; }

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

  log "Installing the Flux controllers"
  f install >/dev/null
  info "$(k -n flux-system get deploy -o name | wc -l | tr -d ' ') controllers installed"

  log "Applying deploy/clusters/k3d"
  k apply -k "$FLUX_DIR" >/dev/null
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
  k -n flux-system wait ocirepository/event-junkie --for=condition=Ready --timeout=2m >/dev/null \
    && ok "range restored, artifact resolves again"
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
      cmd_up && cmd_verify && cmd_import && cmd_chain && cmd_test
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
