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
psql_() { docker exec "$PG_CONTAINER" psql -U admin "$@"; }

cmd_up() {
  require k3d kubectl helm docker yq
  mkdir -p "$STATE_DIR"
  # Saved before k3d switches it, so `down` can put it back exactly.
  kubectl config current-context > "$STATE_DIR/previous-context" 2>/dev/null || true

  log "Building the three images"
  ./gradlew -q :events-bff:bootJarLayers :events-importer:bootJarLayers
  npm --prefix events-frontend run build >/dev/null
  local rev; rev="$(git rev-parse HEAD)"
  local ver; ver="$(grep '^version=' gradle.properties | cut -d= -f2-)"
  for m in bff importer; do
    docker buildx build -f "events-$m/Dockerfile" "events-$m/build/docker" \
      --build-arg "VERSION=$ver" --build-arg "REVISION=$rev" \
      -t "localhost/event-junkie/$m:dev" --load --quiet >/dev/null
    info "built localhost/event-junkie/$m:dev"
  done
  docker buildx build events-frontend --build-arg "VERSION=$ver" --build-arg "REVISION=$rev" \
    -t localhost/event-junkie/frontend:dev --load --quiet >/dev/null
  info "built localhost/event-junkie/frontend:dev"

  log "Creating the cluster"
  # 8080:80 publishes Traefik, which is what makes the ingress testable from the host at all.
  k3d cluster create "$CLUSTER" --port "8080:80@loadbalancer" --agents 1 >/dev/null
  k3d image import -c "$CLUSTER" \
    localhost/event-junkie/bff:dev localhost/event-junkie/importer:dev localhost/event-junkie/frontend:dev >/dev/null
  info "cluster up, images imported ($(k get nodes -o jsonpath='{.items[0].status.nodeInfo.architecture}'))"

  # k3d writes `host.k3d.internal` into the CoreDNS ConfigMap while creating the cluster, but
  # CoreDNS only picks up a ConfigMap change when its `reload` plugin next polls — up to 30 seconds
  # later. Installing inside that window gives every pod that resolves the database host a
  # `java.net.UnknownHostException: host.k3d.internal`, and the importer crash-loops until DNS
  # catches up. It self-heals, which is worse than failing: the install still succeeds and the only
  # evidence is a restart count nobody reads.
  #
  # Restarting CoreDNS forces the reload immediately, so the wait is bounded and explicit rather
  # than a race this script happens to win. Found by running this script — doing the same steps by
  # hand was slow enough to never hit it.
  k -n kube-system rollout restart deployment coredns >/dev/null
  k -n kube-system rollout status deployment coredns --timeout=60s >/dev/null
  info "CoreDNS reloaded — host.k3d.internal resolvable"

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
  k create secret generic events-db --from-literal=username=admin --from-literal=password=admin >/dev/null
  info "database $DB created empty; secret events-db created"

  log "Installing the chart"
  h install "$RELEASE" "$CHART" --values "$VALUES" --wait --timeout 5m >/dev/null
  k get pods -l "app.kubernetes.io/instance=$RELEASE" --no-headers | sed 's/^/   /'
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
  [ -n "$vid" ] && [ "$vid" != "null" ] || die "venue POST failed"
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
    kubectl config use-context "$(cat "$STATE_DIR/previous-context")" >/dev/null 2>&1 \
      && info "kube context restored to $(cat "$STATE_DIR/previous-context")" || true
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
    all)
      # `down` runs even when something above fails, because a half-torn-down rehearsal leaves a
      # k3d context behind that somebody later mistakes for a live cluster.
      trap cmd_down EXIT
      cmd_up && cmd_verify && cmd_import && cmd_chain && cmd_test
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
