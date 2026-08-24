#!/usr/bin/env bash
#
# uid-consistency.sh — the chart and the images must agree about which UID they run as.
#
# Usage:
#   scripts/uid-consistency.sh [chart-dir]      # default: deploy/charts/event-junkie
#
# Requires: yq. Reaches no network, writes nothing, and needs no cluster.
#
# The UID is set in four places that must agree: the `USER` line of each of the three Dockerfiles,
# and `security.runAsUser` / `runAsGroup` in values.yaml plus any per-component override.
#
# **A mismatch does not look like a values problem.** The pod starts and the kubelet is satisfied —
# the UID is non-root either way — and then the JVM cannot read `/application/application.jar`, or
# nginx cannot read its bundle. The symptom is a crash-looping container reporting a permissions
# error from a process that never mentions the chart. `helm unittest` cannot catch it either: it
# asserts the chart renders a specific number, so it sees the chart drifting from itself and not an
# image moving out from under it (#448).
#
# **Every UID and GID must be above 10000** (Trivy KSV-0020 and KSV-0021). Low UIDs collide with
# accounts that already exist on the host: a container escaping its namespace as UID 1000 lands as
# whatever 1000 is on the node, which on a Debian-family host is the first human user. Nothing maps
# to 10001. Asserting the floor here means a well-meaning revert to a "normal" UID fails with an
# explanation attached.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CHART="${1:-$REPO_ROOT/deploy/charts/event-junkie}"
VALUES="$CHART/values.yaml"

# Trivy's KSV-0020/KSV-0021 bar. Named rather than inlined so the error messages can cite it.
readonly UID_FLOOR=10000

command -v yq >/dev/null || {
  printf 'uid-consistency.sh: yq is required but not on PATH\n' >&2
  exit 1
}
[[ -f "$VALUES" ]] || {
  printf 'uid-consistency.sh: no values.yaml at %s\n' "$VALUES" >&2
  exit 1
}

failures=0

fail() {
  printf 'uid-consistency.sh: %s\n' "$1" >&2
  failures=$((failures + 1))
}

# The chart component key and the module directory holding its Dockerfile. Deliberately an explicit
# list rather than something derived: a new component that nobody adds here would otherwise be
# silently unchecked, which is the failure this script exists to prevent, one level up.
COMPONENTS=(
  "bff:events-bff"
  "importer:events-importer"
  "frontend:events-frontend"
)

# The chart-wide defaults every component falls back to.
chart_uid="$(yq -N '.security.runAsUser' "$VALUES")"
chart_gid="$(yq -N '.security.runAsGroup' "$VALUES")"

for pair in "${COMPONENTS[@]}"; do
  component="${pair%%:*}"
  module="${pair#*:}"
  dockerfile="$REPO_ROOT/$module/Dockerfile"

  [[ -f "$dockerfile" ]] || {
    fail "$component: no Dockerfile at $module/Dockerfile"
    continue
  }

  # The last `USER` instruction wins in a Dockerfile, so read the last one rather than the first.
  # Anchored to the start of a line so the paragraphs of commentary above it — which mention `USER`
  # more than once — cannot match.
  user_line="$(grep -E '^USER[[:space:]]' "$dockerfile" | tail -1 || true)"
  [[ -n "$user_line" ]] || {
    fail "$component: $module/Dockerfile has no USER instruction — it would run as root"
    continue
  }

  spec="${user_line#USER }"
  spec="${spec// /}"
  image_uid="${spec%%:*}"
  image_gid="${spec#*:}"

  [[ "$image_uid" =~ ^[0-9]+$ && "$image_gid" =~ ^[0-9]+$ ]] || {
    fail "$component: $module/Dockerfile says 'USER $spec'; it must be numeric <uid>:<gid> (a name needs RUN useradd, which these files must not contain)"
    continue
  }

  # `// ""` rather than `// chart_uid`, so "unset" and "set to the same value" stay distinguishable
  # in the message below — the frontend's override is deliberately unset and that is worth reading.
  override_uid="$(yq -N ".${component}.runAsUser // \"\"" "$VALUES")"
  override_gid="$(yq -N ".${component}.runAsGroup // \"\"" "$VALUES")"
  effective_uid="${override_uid:-$chart_uid}"
  effective_gid="${override_gid:-$chart_gid}"
  source_uid="${override_uid:+${component}.runAsUser}"
  source_uid="${source_uid:-security.runAsUser}"

  if [[ "$effective_uid" != "$image_uid" ]]; then
    fail "$component: $module/Dockerfile runs as UID $image_uid, but the chart's $source_uid is $effective_uid — the pod would not be able to read its own files"
  fi
  if [[ "$effective_gid" != "$image_gid" ]]; then
    fail "$component: $module/Dockerfile runs as GID $image_gid, but the chart resolves to $effective_gid"
  fi

  if [[ "$image_uid" -le "$UID_FLOOR" || "$image_gid" -le "$UID_FLOOR" ]]; then
    fail "$component: $module/Dockerfile runs as $image_uid:$image_gid, which is not above $UID_FLOOR (Trivy KSV-0020/KSV-0021, #448) — a UID in the host's own range lands as a real account if a container escapes its namespace"
  fi
  if [[ "$effective_uid" -le "$UID_FLOOR" || "$effective_gid" -le "$UID_FLOOR" ]]; then
    fail "$component: the chart resolves to $effective_uid:$effective_gid, which is not above $UID_FLOOR (Trivy KSV-0020/KSV-0021, #448)"
  fi

  # Printed whether or not this component failed: what the two sides actually say is the useful
  # thing to read next to the error, and "they agree, and both are too low" is a real verdict.
  printf '%-9s %s:%s  (Dockerfile)  %s  %s:%s  (chart, via %s)\n' \
    "$component" "$image_uid" "$image_gid" \
    "$([[ "$effective_uid" == "$image_uid" && "$effective_gid" == "$image_gid" ]] && echo '==' || echo '!=')" \
    "$effective_uid" "$effective_gid" "$source_uid"
done

if [[ "$failures" -gt 0 ]]; then
  printf '\nuid-consistency.sh: %d problem(s). The three Dockerfiles and values.yaml must agree.\n' "$failures" >&2
  exit 1
fi

printf '\nAll three images and the chart agree, and every UID is above %d.\n' "$UID_FLOOR"
