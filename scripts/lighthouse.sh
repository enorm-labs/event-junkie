#!/usr/bin/env bash
#
# lighthouse.sh — run Lighthouse over the four baseline cells of a deployed origin, three times each,
# and gate on the audits that are deterministic.
#
# Usage:
#   scripts/lighthouse.sh <origin> [output-dir]   # default output-dir: a fresh mktemp -d
#
# Environment:
#   RUNS=3              runs per cell; one run is not a measurement
#   ATTEMPTS=3          attempts per run, for Lighthouse's own NO_NAVSTART flake
#   CHROME_FLAGS        passed to --chrome-flags (default --headless=new)
#   CLS_BUDGET          gate the CLS median when set; unset means report only (see below)
#   GITHUB_STEP_SUMMARY when set, the table is appended there as well as printed
#
# Requires: curl, jq and a Lighthouse CLI. The CLI is `perf/lighthouse/node_modules/.bin/lighthouse`,
# the pin in `perf/lighthouse/package.json`, so `npm ci` there first. `npx --yes lighthouse@13` would
# resolve a different build on every run and is not a pin (#1698).
#
# THE FOUR CELLS ARE `perf/README.md` § Lighthouse baseline: `/de/events` and one event detail page,
# mobile and desktop. The detail slug is resolved from `/api/events` at run time, never written down —
# an event passes and a literal slug becomes a 404 that reads as a regression.
#
# WHAT IS GATED AND WHAT IS ONLY REPORTED. Performance scores from a shared runner move by tens of
# points for reasons that belong to the runner, so a threshold loose enough to survive them catches
# nothing (`perf/README.md` § Why there is no CI workflow). Gated: accessibility and best-practices at
# 100, SEO, and CLS when CLS_BUDGET is set. Reported: the performance score, LCP and TBT.
#
# `is-crawlable` IS HANDLED, NOT WAIVED. `prod-check.event-junkie.de` sends `X-Robots-Tag: noindex,
# nofollow` on purpose (#286), which scores that one audit 0 and the SEO category 69. On a host that is
# not the apex this script asserts the header is really there and that `is-crawlable` is the ONLY
# failing SEO audit, then accepts 69. A second failing audit is red. On the apex it demands 100 and
# grants no exception, so #939's flip needs no edit here.
#
# CLS IS GATED, UNLIKE THE OTHER VITALS. It measures where boxes land, not how fast, so a slow runner
# does not move it. The workflow sets CLS_BUDGET to Google's 0.1 line for a good score (#1207).

set -euo pipefail

case "${1:-}" in
  -h | --help | '') awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"; exit 0 ;;
esac

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ORIGIN="${1%/}"
OUT="${2:-$(mktemp -d)}"
RUNS="${RUNS:-3}"
ATTEMPTS="${ATTEMPTS:-3}"
CHROME_FLAGS="${CHROME_FLAGS:---headless=new}"
LIGHTHOUSE="$REPO_ROOT/perf/lighthouse/node_modules/.bin/lighthouse"

# The apex is the only host that must score 100 on SEO. `www` is a 301 to it and never audited directly.
APEX_HOST='event-junkie.de'

for tool in curl jq; do
  command -v "$tool" >/dev/null || {
    printf 'lighthouse.sh: %s is required but not on PATH\n' "$tool" >&2
    exit 1
  }
done

[[ -x "$LIGHTHOUSE" ]] || {
  # shellcheck disable=SC2016  # the backticks are markdown, not a substitution
  printf 'lighthouse.sh: no Lighthouse CLI at %s — run `npm ci` in perf/lighthouse first\n' "$LIGHTHOUSE" >&2
  exit 1
}

mkdir -p "$OUT"

host="${ORIGIN#*://}"
host="${host%%/*}"
is_apex=0
[[ "$host" == "$APEX_HOST" ]] && is_apex=1

failures=0
fail() {
  printf 'lighthouse.sh: %s\n' "$1" >&2
  printf '::error::%s\n' "$1"
  failures=$((failures + 1))
}

# --- 1. The detail slug, from the API rather than from a literal ---------------------------------
# With a poster: the baseline's detail cell is the page whose LCP is the image, and an event without
# one measures something else. `size=20` is the list page's own page size.
events="$OUT/events.json"
curl -fsS -m 30 --retry 3 --retry-delay 3 -o "$events" "$ORIGIN/api/events?size=20" || {
  printf 'lighthouse.sh: cannot read %s/api/events — nothing to audit\n' "$ORIGIN" >&2
  exit 1
}
slug="$(jq -r 'first(.content[] | select(.imageUrl != null) | .slug) // empty' < "$events")"
[[ -n "$slug" ]] || {
  printf 'lighthouse.sh: no event with a poster in the first 20 of %s/api/events\n' "$ORIGIN" >&2
  exit 1
}

# --- 2. Twelve runs -------------------------------------------------------------------------------
# `list` and `detail` are the row labels in `perf/README.md`; keep them in step with that table.
CELLS=(
  "list|mobile|/de/events"
  "list|desktop|/de/events"
  "detail|mobile|/de/events/$slug"
  "detail|desktop|/de/events/$slug"
)

# A run is retried, and the retry is visible. Lighthouse loses the navigation trace on a fast page
# often enough to have its own error — `NO_NAVSTART`, "Please run Lighthouse again" — and it took two
# of three runs of one cell on the first CI run of this workflow. Retrying is what Lighthouse's own
# message asks for. NOT SILENT: a retried run is printed, because a page that needs three attempts
# every time is a finding even when the third one passes.
for cell in "${CELLS[@]}"; do
  IFS='|' read -r page profile path <<< "$cell"
  preset=()
  [[ "$profile" == desktop ]] && preset=(--preset=desktop)
  for run in $(seq 1 "$RUNS"); do
    report="$OUT/$page-$profile-$run.json"
    for attempt in $(seq 1 "$ATTEMPTS"); do
      if "$LIGHTHOUSE" "$ORIGIN$path" "${preset[@]}" \
        --output=json --output-path="$report" \
        --quiet --chrome-flags="$CHROME_FLAGS"; then
        [[ "$attempt" -eq 1 ]] || printf '::warning::%s/%s run %s needed %s attempts\n' "$page" "$profile" "$run" "$attempt"
        break
      fi
      rm -f "$report"
      [[ "$attempt" -lt "$ATTEMPTS" ]] \
        || fail "Lighthouse failed $ATTEMPTS times on $page/$profile run $run ($ORIGIN$path)"
    done
  done
done
[[ "$failures" -eq 0 ]] || exit 1

# --- 3. Medians, and the audits that failed in every run ------------------------------------------
# A per-metric median of the three runs, which is what the baseline table reports. An audit counts as
# failing only when it failed in ALL runs: one flaky run must not gate a deployment.
# shellcheck disable=SC2016  # jq program: $rows and $id are jq variables, not shell ones
MEDIANS_JQ='
  def mid: .[(length / 2) | floor];
  def med(f): map(f) | sort | mid;
  def scores(c): map(.categories[c].score * 100 | round) | sort;
  def rng: if .[0] == .[-1] then "" else " (\(.[0])–\(.[-1]))" end;
  . as $rows
  | {
      performance:      (scores("performance")    | mid),
      accessibility:    (scores("accessibility")  | mid),
      bestPractices:    (scores("best-practices") | mid),
      seo:              (scores("seo")            | mid),
      performanceRange: (scores("performance")    | rng),
      lcpMs: med(.audits["largest-contentful-paint"].numericValue),
      cls:   med(.audits["cumulative-layout-shift"].numericValue),
      tbtMs: med(.audits["total-blocking-time"].numericValue),
      failing: ([ $rows[0].categories
                  | to_entries[]
                  | select(.key | IN("accessibility", "best-practices", "seo"))
                  | .key as $category
                  | .value.auditRefs[].id
                  | { category: $category, id: . } ]
                | unique
                | map(select(.id as $id
                             | [ $rows[] | .audits[$id].score | (. != null and . < 1) ] | all)))
    }'

medians() { jq -s "$MEDIANS_JQ" "$@"; }

# The audit's own title, for the message that names it.
# shellcheck disable=SC2016  # jq program
audit_title() { jq -r --arg id "$2" '.audits[$id].title' < "$1"; }

rows=()
for cell in "${CELLS[@]}"; do
  IFS='|' read -r page profile path <<< "$cell"
  files=()
  for run in $(seq 1 "$RUNS"); do files+=("$OUT/$page-$profile-$run.json"); done
  summary="$(medians "${files[@]}")"
  printf '%s\n' "$summary" > "$OUT/$page-$profile-median.json"

  performance="$(jq -r '.performance' <<< "$summary")"
  performance_range="$(jq -r '.performanceRange' <<< "$summary")"
  accessibility="$(jq -r '.accessibility' <<< "$summary")"
  best_practices="$(jq -r '.bestPractices' <<< "$summary")"
  seo="$(jq -r '.seo' <<< "$summary")"
  lcp="$(jq -r '(.lcpMs / 1000 * 10 | round) / 10' <<< "$summary")"
  cls="$(jq -r '(.cls * 100 | round) / 100' <<< "$summary")"
  tbt="$(jq -r '.tbtMs | round' <<< "$summary")"

  label="$path"
  [[ "$page" == detail ]] && label='/de/events/<slug>'
  rows+=("| \`$label\` | $profile | $performance$performance_range | $accessibility | $best_practices | $seo | ${lcp} s | $cls | ${tbt} ms |")

  cell_name="$page/$profile"
  first="$OUT/$page-$profile-1.json"

  # --- The gate ---------------------------------------------------------------------------------
  [[ "$accessibility" -eq 100 ]] || fail "$cell_name: accessibility is $accessibility, expected 100."
  [[ "$best_practices" -eq 100 ]] || fail "$cell_name: best-practices is $best_practices, expected 100."

  seo_failing="$(jq -r '[ .failing[] | select(.category == "seo") | .id ] | join(" ")' <<< "$summary")"

  if [[ "$is_apex" -eq 1 ]]; then
    [[ "$seo" -eq 100 ]] || fail "$cell_name: SEO is $seo on the apex, expected 100. Failing: ${seo_failing:-none}"
  elif [[ "$seo_failing" == 'is-crawlable' ]]; then
    :
  elif [[ -z "$seo_failing" ]]; then
    fail "$cell_name: SEO has no failing audit on $host, but that host is expected to send X-Robots-Tag: noindex. Either the override (#286) is gone, or SITE_URL now names the apex and APEX_HOST here is stale."
  else
    fail "$cell_name: SEO is $seo on $host and the failing audits are '$seo_failing'. Only 'is-crawlable' is expected there (#286)."
  fi

  if [[ -n "${CLS_BUDGET:-}" ]]; then
    jq -e --arg budget "$CLS_BUDGET" '.cls <= ($budget | tonumber)' <<< "$summary" > /dev/null \
      || fail "$cell_name: CLS median is $cls, over the $CLS_BUDGET budget (#1207)."
  fi

  # Every other failing audit in a GATED category is named, so a reader does not open the JSON to find
  # it. Performance audits are deliberately not listed: most of them read below 1 on a healthy page,
  # and a list nobody can act on is a list nobody reads.
  while IFS='|' read -r category id; do
    [[ -z "$id" || "$id" == 'is-crawlable' ]] && continue
    printf '::warning::%s: %s failed (%s) — %s\n' \
      "$cell_name" "$id" "$category" "$(audit_title "$first" "$id")"
  done < <(jq -r '.failing[] | "\(.category)|\(.id)"' <<< "$summary")
done

# --- 4. The noindex header, asserted rather than assumed -------------------------------------------
# The SEO exception above rests on this header existing. Reading it is what makes the exception a
# check: a host that lost the override would otherwise pass as "not the apex, 69 is fine".
if [[ "$is_apex" -eq 0 ]]; then
  robots="$(curl -fsSI -m 20 --retry 2 "$ORIGIN/de/events" | tr -d '\r' | awk 'tolower($1) == "x-robots-tag:" { $1 = ""; sub(/^ /, ""); print }')"
  [[ "$robots" == *noindex* ]] || fail "$host serves no 'X-Robots-Tag: noindex' on /de/events (got '${robots:-none}'), so the SEO exception for is-crawlable does not hold (#286)."
fi

# --- 5. The table ----------------------------------------------------------------------------------
lh_version="$("$LIGHTHOUSE" --version)"
chrome_version="$(jq -r '.environment.hostUserAgent' < "$OUT/list-mobile-1.json")"

{
  printf '## Lighthouse — %s\n\n' "$ORIGIN"
  printf 'Medians of %s runs per cell. Lighthouse %s, %s.\n\n' "$RUNS" "$lh_version" "$chrome_version"
  printf '| Page | Profile | Performance | Accessibility | Best practices | SEO | LCP | CLS | TBT |\n'
  printf '| ---- | ------- | ----------- | ------------- | -------------- | --- | --- | --- | --- |\n'
  printf '%s\n' "${rows[@]}"
  printf '\n'
  if [[ "$is_apex" -eq 0 ]]; then
    # shellcheck disable=SC2016  # the backticks are markdown, not a substitution
    printf '`%s` is not the apex: it sends `X-Robots-Tag: noindex, nofollow` on purpose (#286), so `is-crawlable` scores 0 and SEO reads 69. Asserted, not waived.\n\n' "$host"
  fi
  if [[ -z "${CLS_BUDGET:-}" ]]; then
    # shellcheck disable=SC2016  # the backticks are markdown, not a substitution
    printf 'CLS is reported and not gated: `CLS_BUDGET` is unset.\n'
  fi
} | tee -a "${GITHUB_STEP_SUMMARY:-/dev/null}"

[[ "$failures" -eq 0 ]] || {
  printf 'lighthouse.sh: %s gate failure(s)\n' "$failures" >&2
  exit 1
}
printf 'lighthouse.sh: %s cells passed the gate, reports in %s\n' "${#CELLS[@]}" "$OUT"
