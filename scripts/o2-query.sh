#!/usr/bin/env bash
#
# o2-query.sh — read an environment's logs, events and metrics out of OpenObserve, from a terminal.
#
# Usage:
#   scripts/o2-query.sh <staging|production> sweep [--hours N]           # the /log-check sweep, one JSON object
#   scripts/o2-query.sh <staging|production> traffic [--hours N]         # requests and visitor estimates per day
#   scripts/o2-query.sh <staging|production> renderers [--hours N]       # which crawlers run the SPA; use --hours 2
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
# are upper bounds, not visitors; OPENOBSERVE.md § Traffic says what each column counts. `renderers`
# says which crawlers run the SPA, which is what `traffic` must subtract from its sessions.
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
    sweep | traffic | renderers) ;;
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

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"; [ -z "${FORWARD:-}" ] || kill "$FORWARD" 2>/dev/null || true' EXIT
if ! curl -s -o /dev/null --max-time 3 "$BASE/healthz"; then
    kubectl --context "$CONTEXT" -n observability port-forward svc/openobserve-openobserve-standalone "${PORT}:5080" >/dev/null 2>&1 &
    FORWARD=$!
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

# User agents that are not a person: crawlers, our own monitors and tests, scanners, and a missing
# agent. An agent that names a URL is a scanner probing for WordPress. Three carry no bot word:
# `Nexus 5X Build/MMB29P` is Google's renderer, `moto g power` is Lighthouse mobile (ours or PageSpeed
# Insights), and `Chrome/48.0.2564.116` is an old crawler. Lighthouse desktop sends the user agent of
# real Mac Chrome, so no pattern can remove it.
NOT_A_PERSON='(?i)bot|spider|crawl|slurp|facebookexternalhit|Better Stack|Uptime|lighthouse|Headless|curl|python|Go-http|"node"|k6|zgrab|nuclei|ZAP|wget|scan|http://|injector|akiaenvgo|Nexus 5X Build/MMB29P|moto g power|Chrome/48\.0\.2564\.116|"-"$'
OURS='(?i)Better Stack|lighthouse|moto g power|Headless|"node"|nuclei|ZAP|injector'
# A page load is a GET for an HTML route that answers 200. Assets, probes and 404s are not.
PAGE_LOAD='^\[[^]]+\] "GET /(|de|en|de/[^ .]*|en/[^ .]*)(\?[^ ]*)? HTTP/[0-9.]+" 200 '
DETAIL_PAGE='^\[[^]]+\] "GET /(de|en)/(events|venues|artists|promoters)/[^ /?]+/?(\?[^ ]*)? HTTP/[0-9.]+" 200 '
DETAIL_CALL='^/api/(events|venues|artists|promoters)/[^/]+$'
# The crawlers that run the SPA on the pages they fetch, as `renderers` measures them, each with the
# seconds after the page load in which its SPA calls the BFF.
RENDERERS=(AhrefsBot:0:2 Reflectionbot:0:7 Baiduspider:2:12 Googlebot:1:12)
RENDERER=$(printf '%s\n' "${RENDERERS[@]}" | cut -d: -f1 | paste -sd '|' -)

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
        FRONTEND=$(search "SELECT date_trunc('hour', to_timestamp_micros(_timestamp)) AS h, COUNT(*) AS total,
            sum(CASE WHEN NOT regexp_like(body, '$NOT_A_PERSON') AND regexp_like(body, '$PAGE_LOAD') THEN 1 ELSE 0 END) AS pages,
            sum(CASE WHEN regexp_like(body, '$OURS') THEN 1 ELSE 0 END) AS ours,
            sum(CASE WHEN regexp_like(body, '(?i)bot|spider|crawl|slurp') AND NOT str_match(body, 'Better Stack') THEN 1 ELSE 0 END) AS crawlers,
            sum(CASE WHEN regexp_like(body, '$RENDERER') AND regexp_like(body, '$PAGE_LOAD') THEN 1 ELSE 0 END) AS renders,
            sum(CASE WHEN regexp_like(body, '$RENDERER') AND regexp_like(body, '$DETAIL_PAGE') THEN 1 ELSE 0 END) AS rendered_details,
            sum(CASE WHEN regexp_like(body, '$NOT_A_PERSON') AND regexp_like(body, '$DETAIL_PAGE') THEN 1 ELSE 0 END) AS injected_details
            FROM default WHERE k8s_app_component = 'frontend' AND regexp_like(body, '^\[') GROUP BY h ORDER BY h")
        # The SPA calls /api/meta once when it starts, so it counts sessions in a browser that runs
        # JavaScript. Only the SPA calls a list endpoint. The injector calls a detail endpoint for
        # every detail page nginx serves, and the SPA calls it again.
        BFF=$(search "SELECT date_trunc('hour', to_timestamp_micros(_timestamp)) AS h, COUNT(*) AS total,
            sum(CASE WHEN path = '/api/meta' THEN 1 ELSE 0 END) AS meta,
            sum(CASE WHEN regexp_like(path, '^/api/(events|events/today|venues|artists|genres|promoters)$') THEN 1 ELSE 0 END) AS lists,
            sum(CASE WHEN regexp_like(path, '$DETAIL_CALL') AND path <> '/api/events/today' THEN 1 ELSE 0 END) AS details,
            sum(CASE WHEN str_match(path, '/api/images/') THEN 1 ELSE 0 END) AS images
            FROM default WHERE k8s_app_component = 'bff' AND logger = 'de.norm.events.RequestLoggingFilter' GROUP BY h ORDER BY h")
        # A renderer calls /api/meta as well, and the BFF log names no agent. So a call in the seconds
        # after a renderer's page load counts as the renderer's. A person's call in such a second is
        # lost too, so each hour's remainder is scaled up by the share of the hour left uncovered.
        search "SELECT _timestamp, body FROM default WHERE k8s_app_component = 'frontend'
            AND regexp_like(body, '$RENDERER') AND regexp_like(body, '$PAGE_LOAD') LIMIT 1000000" >"$WORK/renders.json"
        search "SELECT _timestamp FROM default WHERE k8s_app_component = 'bff'
            AND logger = 'de.norm.events.RequestLoggingFilter' AND path = '/api/meta' LIMIT 1000000" >"$WORK/meta.json"
        # Details leave out one call per detail page a renderer runs and one per detail page any
        # non-person agent fetches, for the injector. `night_sessions` covers 00:00-04:00 UTC: a
        # steady count there is a renderer that `renderers` has not found yet, not people.
        jq -n \
            --arg environment "$ENVIRONMENT" --argjson hours "$HOURS" --argjson burst "$BURST" \
            --arg from "$(date -u -r "$START_S" +%FT%TZ 2>/dev/null || date -u -d "@$START_S" +%FT%TZ)" \
            --argjson fe "$FRONTEND" --argjson bff "$BFF" \
            --slurpfile renders "$WORK/renders.json" --slurpfile meta "$WORK/meta.json" \
            --argjson windows "$(printf '%s\n' "${RENDERERS[@]}" | jq -R 'split(":") | {(.[0]): (.[1:] | map(tonumber))}' | jq -s -c add)" '
            def total(f): map(f) | add // 0;
            def epoch: fromdateiso8601;
            def hour: . - . % 3600 | todate | .[0:19];
            ($fe | map(select(.total > $burst) | .h)) as $bursts
            | def quiet: (.h as $h | $bursts | index($h)) == null;
            def night: (.h[11:13] | tonumber) < 4;
            (reduce $renders[0][] as $r ({};
                ($r._timestamp | epoch) as $t
                | ($windows | to_entries | map(select(.key as $k | $r.body | contains($k))) | first.value) as $w
                | reduce range($t + $w[0]; $t + $w[1] + 1) as $s (.; .[$s | tostring] = true))) as $covered
            | (reduce ($covered | keys[]) as $k ({}; .[$k | tonumber | hour] += 1)) as $covered_by_hour
            | (reduce $meta[0][] as $m ({}; ($m._timestamp | epoch) as $t
                | if $covered[$t | tostring] then . else .[$t | hour] += 1 end)) as $left_by_hour
            | def sessions: ($left_by_hour[.h] // 0) / (1 - ([($covered_by_hour[.h] // 0), 3540] | min) / 3600);
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
                         sessions: ($bq | total(sessions) | round),
                         night_sessions: ($bq | map(select(night)) | total(sessions) | round),
                         lists: ($bq | total(.lists)), details: ($bq | total(.details)),
                         details_by_people: ([($bq | total(.details)) - ($fq | total(.rendered_details + .injected_details)), 0] | max),
                         images: ($bq | total(.images))}}]
            | {environment: $environment, hours: $hours, from: $from, burst_threshold: $burst, renderers: $windows,
               totals: {page_loads: total(.frontend.page_loads), sessions: total(.bff.sessions),
                        lists: total(.bff.lists), details_by_people: total(.bff.details_by_people), images: total(.bff.images)},
               days: .}'
        ;;
    renderers)
        # The injector calls the detail endpoint once for every detail page nginx serves. A second call
        # for the same slug within 15 seconds comes from the SPA, so the agent that fetched the page ran
        # it. One page per agent and slug is enough; a person on the same slug in that window is rare.
        # Reads every line, so keep the window short: --hours 2 is about 1,000 pages.
        search "SELECT _timestamp, body FROM default WHERE k8s_app_component = 'frontend'
            AND regexp_like(body, '$DETAIL_PAGE') AND regexp_like(body, '$NOT_A_PERSON') LIMIT 1000000" >"$WORK/pages.json"
        search "SELECT _timestamp, path FROM default WHERE k8s_app_component = 'bff'
            AND logger = 'de.norm.events.RequestLoggingFilter' AND regexp_like(path, '$DETAIL_CALL') LIMIT 1000000" >"$WORK/calls.json"
        # `delay` is the spread of seconds from page load to the SPA's call, 5th to 95th percentile: the
        # window to give a crawler added to RENDERERS.
        jq -n --arg environment "$ENVIRONMENT" --argjson hours "$HOURS" \
            --slurpfile pages "$WORK/pages.json" --slurpfile calls "$WORK/calls.json" --arg listed "$RENDERER" '
            def epoch: fromdateiso8601;
            ($calls[0] | group_by(.path) | map({key: .[0].path, value: map(._timestamp | epoch)}) | from_entries) as $at
            | [$pages[0][]
                | (.body | capture("\"GET /(de|en)/(?<kind>[a-z]+)/(?<slug>[^ /?]+)")) as $p
                | (.body | capture("\"(?<ua>[^\"]*)\"$").ua) as $ua
                | (._timestamp | epoch) as $t
                | {agent: ($ua | (capture("(?i)(?<a>[A-Za-z.-]*(bot|spider|crawl|slurp)[A-Za-z.-]*)").a // capture("(?<a>Nexus 5X|moto g power|facebookexternalhit)").a // $ua[0:60])),
                   calls: [($at["/api/\($p.kind)/\($p.slug)"] // [])[] | select(. >= $t - 1 and . <= $t + 15) - $t] | sort}
                | .rendered = (.calls | length >= 2) | .delay = .calls[-1]]
            | group_by(.agent)
            | map((map(select(.rendered) | .delay) | sort) as $d
                | {agent: .[0].agent, detail_pages: length, rendered: ($d | length),
                   delay: (if $d == [] then null else [$d[($d | length) * 5 / 100 | floor], $d[($d | length) * 95 / 100 | floor]] end)}
                | .share = ((.rendered * 100 / .detail_pages) | floor)
                | .subtracted = (.agent | test($listed)))
            | sort_by(-.rendered)
            | {environment: $environment, hours: $hours, agents: .}'
        ;;
esac
