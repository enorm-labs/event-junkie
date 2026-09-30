#!/usr/bin/env bash
#
# o2-query.sh — read an environment's logs, events and metrics out of OpenObserve, from a terminal.
#
# Usage:
#   scripts/o2-query.sh <staging|production> sweep [--hours N]           # the /log-check sweep, one JSON object
#   scripts/o2-query.sh <staging|production> traffic [--hours N]         # requests and visitor estimates per day
#   scripts/o2-query.sh <staging|production> sql '<SQL>' [--hours N]     # one search; prints the hits
#   scripts/o2-query.sh <staging|production> promql '<expr>' [--hours N] [--step S]
#
# Needs the WireGuard tunnel up and a kubeconfig context named event-junkie-<environment>. Reuses the
# forward `scripts/ej.sh up` starts (5080 staging, 25080 production); starts its own on that port when
# none answers, and stops it on exit. Reads only. The root credential comes from the
# observability/openobserve-credentials Secret into a variable and is never printed or written.
#
# --hours defaults to 24. The window ends now. `sweep` groups what a person reads first: ERROR and
# WARN lines, unstructured lines that name a failure, 5xx answers, Kubernetes Warning events and
# every alert that fired. Each group carries a count, the first and last time and the newest version.
# `traffic` counts nginx page loads and BFF calls per day. Neither log names a visitor, so both counts
# are upper bounds, not visitors; OPENOBSERVE.md § Traffic says what each column counts.
#
# The field names are not the ones the code writes: the message is `body`, fields are lower-case,
# and a line that is not JSON (nginx, Flux, a helm test pod) has `severity = '0'`. OPENOBSERVE.md has
# the streams. Exit code: 0 on an answer, 1 when OpenObserve refuses the query, 2 on bad arguments.
set -euo pipefail

case "${1:-}" in
    -h | --help)
        awk '/^# Usage:/ { p = 1 } p && !/^#./ { exit } p { sub(/^# ?/, ""); print }' "$0"
        exit 0
        ;;
esac

die() {
    echo "o2-query: $1" >&2
    exit 2
}

[ $# -ge 2 ] || die "needs an environment and a verb; see --help"
ENVIRONMENT="$1"
VERB="$2"
shift 2
case "$ENVIRONMENT" in
    staging) PORT=5080 ;;
    production) PORT=25080 ;;
    *) die "unknown environment '$ENVIRONMENT'" ;;
esac

QUERY=
case "$VERB" in
    sweep | traffic) ;;
    sql | promql)
        [ $# -ge 1 ] || die "$VERB needs a query"
        QUERY="$1"
        shift
        ;;
    *) die "unknown verb '$VERB'" ;;
esac

HOURS=24
STEP=300
while [ $# -gt 0 ]; do
    case "$1" in
        --hours)
            HOURS="${2:-}"
            shift
            ;;
        --step)
            STEP="${2:-}"
            shift
            ;;
        *) die "unknown argument '$1'" ;;
    esac
    shift
done
[[ "$HOURS" =~ ^[0-9]+$ ]] || die "--hours needs a whole number"
[[ "$STEP" =~ ^[0-9]+$ ]] || die "--step needs a whole number of seconds"

CONTEXT="event-junkie-${ENVIRONMENT}"
BASE="http://127.0.0.1:${PORT}"

if ! curl -s -o /dev/null --max-time 3 "$BASE/healthz"; then
    kubectl --context "$CONTEXT" -n observability port-forward svc/openobserve-openobserve-standalone "${PORT}:5080" >/dev/null 2>&1 &
    FORWARD=$!
    trap 'kill "$FORWARD" 2>/dev/null || true' EXIT
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        curl -s -o /dev/null --max-time 2 "$BASE/healthz" && break
        sleep 1
    done
fi

secret() {
    kubectl --context "$CONTEXT" -n observability get secret openobserve-credentials -o "jsonpath={.data.$1}" | base64 -d
}
AUTH="$(secret ZO_ROOT_USER_EMAIL):$(secret ZO_ROOT_USER_PASSWORD)"

END_S=$(date +%s)
START_S=$((END_S - HOURS * 3600))

# One search. Prints the hits as a JSON array, timestamps (microseconds) as ISO 8601.
search() {
    local answer
    answer=$(jq -n --arg sql "$1" --argjson s "$((START_S * 1000000))" --argjson e "$((END_S * 1000000))" \
        '{query: {sql: $sql, start_time: $s, end_time: $e, size: 500}}' |
        curl -s -u "$AUTH" -H 'Content-Type: application/json' "$BASE/api/default/_search" -d @-)
    if ! jq -e '.hits' >/dev/null 2>&1 <<<"$answer"; then
        echo "o2-query: OpenObserve refused the query: $(jq -r '.message // .' <<<"$answer" 2>/dev/null | head -c 300)" >&2
        exit 1
    fi
    jq '[.hits[] | with_entries(if (.key | test("^(_timestamp|first|last)$")) and (.value | type) == "number"
        then .value |= (. / 1000000 | floor | todate) else . end)]' <<<"$answer"
}

case "$VERB" in
    sql)
        search "$QUERY"
        ;;
    promql)
        curl -s -u "$AUTH" "$BASE/api/default/prometheus/api/v1/query_range" --data-urlencode "query=$QUERY" \
            --data-urlencode "start=$START_S" --data-urlencode "end=$END_S" --data-urlencode "step=$STEP"
        ;;
    sweep)
        # The shared columns of every log group. Digits become N in the grouping key, or every nginx
        # and Flux line is a group of one; `sample` keeps one line as written. max(service_version) is
        # the newest build the group reached, which separates a rollout from what runs now.
        LOG_COLUMNS="k8s_app_component AS component, logger, errortype, regexp_replace(substr(body, 1, 160), '[0-9]+', 'N', 'g') AS message, min(substr(body, 1, 300)) AS sample, COUNT(*) AS n, min(_timestamp) AS first, max(_timestamp) AS last, max(service_version) AS version"
        LOG_GROUP="GROUP BY component, logger, errortype, message ORDER BY n DESC LIMIT 60"
        # An nginx access line carries the request path, so a slug or a scanner's URL can name a failure
        # word. Below 5xx it is no failure; OpenObserve's re_match has no \b to anchor the words instead.
        ACCESS_BELOW_5XX="re_match(body, '^\\[[^]]+\\] \"[^\"]*\" [1-4][0-9][0-9] ')"
        # The collector re-sends each event about an hour later with a new resourceVersion, so a row
        # count doubles it. One occurrence is one event name at one count.
        K8S_MESSAGE="regexp_replace(substr(body_object_note, 1, 160), '[0-9]+', 'N', 'g')"
        jq -n \
            --arg environment "$ENVIRONMENT" --argjson hours "$HOURS" \
            --arg from "$(date -u -r "$START_S" +%FT%TZ 2>/dev/null || date -u -d "@$START_S" +%FT%TZ)" \
            --argjson totals "$(search "SELECT k8s_app_component AS component, severity, COUNT(*) AS n FROM default GROUP BY component, severity ORDER BY n DESC")" \
            --argjson errors "$(search "SELECT $LOG_COLUMNS FROM default WHERE severity = 'ERROR' $LOG_GROUP")" \
            --argjson warnings "$(search "SELECT $LOG_COLUMNS FROM default WHERE severity = 'WARN' $LOG_GROUP")" \
            --argjson unstructured "$(search "SELECT $LOG_COLUMNS FROM default WHERE severity = '0' AND NOT str_match(body, '\"level\":\"info\"') AND re_match(body, '(?i)error|fail|panic|fatal|exception|refused|denied|timeout') AND NOT $ACCESS_BELOW_5XX $LOG_GROUP")" \
            --argjson http5xx "$(search "SELECT k8s_app_component AS component, logger, httpstatus, COUNT(*) AS n, min(_timestamp) AS first, max(_timestamp) AS last FROM default WHERE httpstatus >= 500 GROUP BY component, logger, httpstatus ORDER BY n DESC")" \
            --argjson k8s_warnings "$(search "SELECT body_object_reason AS reason, $K8S_MESSAGE AS message, min(substr(body_object_note, 1, 300)) AS sample, COUNT(DISTINCT concat(event_name, '/', body_object_deprecatedcount)) AS n, min(_timestamp) AS first, max(_timestamp) AS last FROM k8s_events WHERE body_object_type = 'Warning' GROUP BY reason, message ORDER BY n DESC LIMIT 40")" \
            --argjson alerts "$(search "SELECT _timestamp, alert, value FROM alert_history ORDER BY _timestamp DESC LIMIT 200")" \
            '{environment: $environment, hours: $hours, from: $from, totals: $totals, errors: $errors, warnings: $warnings,
              unstructured: $unstructured, http5xx: $http5xx, k8s_warnings: $k8s_warnings, alerts: $alerts}'
        ;;
    traffic)
        # An hour above this many nginx lines is a scan or a crawl: the weekly DAST run, or a crawler
        # walking the sitemap. Such an hour counts as requests and never as a visitor.
        BURST=600
        # User agents that are not a person: crawlers, our own monitors and tests, scanners, and a
        # missing agent. An agent that names a URL is a scanner probing for WordPress.
        NOT_A_PERSON='(?i)bot|spider|crawl|slurp|facebookexternalhit|Better Stack|Uptime|lighthouse|Headless|curl|python|Go-http|"node"|k6|zgrab|nuclei|ZAP|wget|scan|http://|injector|akiaenvgo|"-"$'
        OURS='(?i)Better Stack|lighthouse|Headless|"node"|nuclei|ZAP|injector'
        # A page load is a GET for an HTML route that answers 200. Assets, probes and 404s are not.
        PAGE_LOAD='^\[[^]]+\] "GET /(|de|en|de/[^ .]*|en/[^ .]*)(\?[^ ]*)? HTTP/[0-9.]+" 200 '
        DETAIL_PAGE='^\[[^]]+\] "GET /(de|en)/(events|venues|artists|promoters)/[^ /?]+/?(\?[^ ]*)? HTTP/[0-9.]+" 200 '
        # Baiduspider runs the SPA on the page it fetches, so each fetch also calls /api/meta and the
        # detail endpoint. Googlebot and ClaudeBot do not: their fetches meet /api/meta only by chance.
        RENDERER='Baiduspider'
        FRONTEND=$(search "SELECT date_trunc('hour', to_timestamp_micros(_timestamp)) AS h, COUNT(*) AS total,
            sum(CASE WHEN NOT regexp_like(body, '$NOT_A_PERSON') AND regexp_like(body, '$PAGE_LOAD') THEN 1 ELSE 0 END) AS pages,
            sum(CASE WHEN regexp_like(body, '$OURS') THEN 1 ELSE 0 END) AS ours,
            sum(CASE WHEN regexp_like(body, '(?i)bot|spider|crawl|slurp') AND NOT str_match(body, 'Better Stack') THEN 1 ELSE 0 END) AS crawlers,
            sum(CASE WHEN str_match(body, '$RENDERER') AND regexp_like(body, '$PAGE_LOAD') THEN 1 ELSE 0 END) AS renders,
            sum(CASE WHEN str_match(body, '$RENDERER') AND regexp_like(body, '$DETAIL_PAGE') THEN 1 ELSE 0 END) AS rendered_details,
            sum(CASE WHEN regexp_like(body, '$NOT_A_PERSON') AND regexp_like(body, '$DETAIL_PAGE') THEN 1 ELSE 0 END) AS injected_details
            FROM default WHERE k8s_app_component = 'frontend' AND regexp_like(body, '^\[') GROUP BY h ORDER BY h")
        # The SPA calls /api/meta once when it starts, so it counts sessions in a browser that runs
        # JavaScript. Only the SPA calls a list endpoint. The injector calls a detail endpoint for
        # every detail page nginx serves, and the SPA calls it again.
        BFF=$(search "SELECT date_trunc('hour', to_timestamp_micros(_timestamp)) AS h, COUNT(*) AS total,
            sum(CASE WHEN path = '/api/meta' THEN 1 ELSE 0 END) AS meta,
            sum(CASE WHEN regexp_like(path, '^/api/(events|events/today|venues|artists|genres|promoters)$') THEN 1 ELSE 0 END) AS lists,
            sum(CASE WHEN regexp_like(path, '^/api/(events|venues|artists|promoters)/[^/]+$') AND path <> '/api/events/today' THEN 1 ELSE 0 END) AS details,
            sum(CASE WHEN str_match(path, '/api/images/') THEN 1 ELSE 0 END) AS images
            FROM default WHERE k8s_app_component = 'bff' AND logger = 'de.norm.events.RequestLoggingFilter' GROUP BY h ORDER BY h")
        # Sessions and details leave out what crawlers cause: one /api/meta call per page Baiduspider
        # renders, and one detail call per detail page it renders or any non-person agent fetches.
        jq -n \
            --arg environment "$ENVIRONMENT" --argjson hours "$HOURS" --argjson burst "$BURST" \
            --arg from "$(date -u -r "$START_S" +%FT%TZ 2>/dev/null || date -u -d "@$START_S" +%FT%TZ)" \
            --argjson fe "$FRONTEND" --argjson bff "$BFF" '
            def total(f): map(f) | add // 0;
            ($fe | map(select(.total > $burst) | .h)) as $bursts
            | def quiet: (.h as $h | $bursts | index($h)) == null;
            [([$fe[], $bff[]] | map(.h[0:10]) | unique)[] as $day
                | ($fe | map(select(.h[0:10] == $day))) as $f
                | ($bff | map(select(.h[0:10] == $day))) as $b
                | ($b | map(select(quiet))) as $bq
                | ($f | map(select(quiet))) as $fq
                | {day: $day,
                   frontend: {requests: ($f | total(.total)), in_burst: ($f | map(select(quiet | not)) | total(.total)),
                              own_monitoring: ($f | total(.ours)), crawlers: ($f | total(.crawlers)),
                              page_loads: ($fq | total(.pages)), rendered_by_crawlers: ($fq | total(.renders))},
                   bff: {requests: ($b | total(.total)), in_burst: ($b | map(select(quiet | not)) | total(.total)),
                         meta: ($bq | total(.meta)),
                         sessions: ([($bq | total(.meta)) - ($fq | total(.renders)), 0] | max),
                         lists: ($bq | total(.lists)), details: ($bq | total(.details)),
                         details_by_people: ([($bq | total(.details)) - ($fq | total(.rendered_details + .injected_details)), 0] | max),
                         images: ($bq | total(.images))}}]
            | {environment: $environment, hours: $hours, from: $from, burst_threshold: $burst,
               totals: {page_loads: total(.frontend.page_loads), sessions: total(.bff.sessions),
                        lists: total(.bff.lists), details_by_people: total(.bff.details_by_people), images: total(.bff.images)},
               days: .}'
        ;;
esac
