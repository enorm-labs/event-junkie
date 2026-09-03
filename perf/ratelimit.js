/**
 * Rate-limit test — does the per-source limit stop abuse without touching a visitor?
 *
 * The other three scripts measure the application. This one measures the **Traefik middleware in
 * front of it** (`ingress.rateLimit.perSource`, #268), so it only says anything against a deployed
 * environment. Run against a laptop it measures nothing and passes, which is why it is not in the
 * default rotation.
 *
 * It answers two questions, and either one alone is worthless:
 *
 *   - **Does ordinary browsing survive it?** A 429 during the `browsing` scenario fails the run.
 *     This is the half that matters, because a too-tight limit does not look like an outage — a
 *     rejected font or venue image is a half-styled page, and nobody reports it.
 *   - **Does abuse actually get stopped?** Zero 429s during the `abuse` scenario also fails the
 *     run. A limit that never engages is indistinguishable from no limit, which is the state this
 *     whole issue was filed against. **The concurrency there has to stay under
 *     `ingress.rateLimit.inFlightRequests`** — see `ABUSE_STREAMS`, because a 429 from the wrong
 *     limit passes this test against a per-source limit that is switched off.
 *
 * **The scenarios run in sequence, never together**, because they share a source address — this
 * machine's — and therefore one token bucket. `abuse` starts after `browsing` has finished and the
 * bucket has had time to refill. Do not "speed it up" by shortening `startTime`: cutting it to 12
 * seconds against healthy staging put the flood on top of the visitor and reported
 * `visitor rejected ... 10  → TOO TIGHT`, which is a verdict about the schedule rather than the
 * limit.
 *
 * **The unit is a page view, not an API call.** One Ingress carries the whole site, so the budget
 * is spent by the HTML, the JS chunks, the fonts and every venue image as well as the queries.
 * `browsing` therefore fetches all of them, the way a browser would on a first visit — which is
 * also the worst case, since everything but the HTML is served `immutable`.
 *
 *   k6 run -e BFF_HOST=https://staging.event-junkie.de \
 *          -e RESOLVE=staging.event-junkie.de:10.10.1.1 -e INSECURE=true perf/ratelimit.js
 */
import http from 'k6/http'
import {check, sleep} from 'k6'
import {Counter} from 'k6/metrics'

import {BASE_URL} from './lib/config.js'

/** The site's origin. `BASE_URL` is the origin plus `/api`; static assets and the HTML are not. */
const ORIGIN = BASE_URL.replace(/\/api$/, '')

/**
 * Concurrent streams the abuse scenario runs, and **the number that makes this test mean anything.**
 *
 * It has to stay far below `ingress.rateLimit.inFlightRequests` (100), because Traefik answers both
 * limits with a bare 429 and nothing in the response says which one fired. An abuse scenario with
 * 200 VUs therefore trips the *concurrency* limit and reports a pass while the per-source limit is
 * switched off — measured on staging before this landed: 300 parallel requests gave 66 rejections
 * with `perSource.enabled: false`.
 *
 * Ten sequential streams cannot reach the concurrency cap, and still push about 80 requests a second
 * — above `average` and below `inFlightRequests`. A 429 in this scenario can only be the per-source
 * limit. The same 400 requests against the disabled limit produced zero.
 */
const ABUSE_STREAMS = Number(__ENV.ABUSE_STREAMS || 10)

/**
 * Rejections, counted per scenario rather than in aggregate.
 *
 * Two counters instead of one tagged metric because the thresholds are *opposites* — one must be
 * zero and the other must not — and a single metric with a tag filter reads as if the two were
 * measuring the same thing.
 */
const browsingRejected = new Counter('rl_rejected_browsing')
const abuseRejected = new Counter('rl_rejected_abuse')

/**
 * Anything that answered neither 200 nor 429, which is **the counter that stops this script lying.**
 *
 * The two above ask whether a request was *rejected by the limit*, and a 404 is not a 429. So a site
 * that is not routing at all scores zero rejections in both scenarios, and the verdict reads as a
 * limit nobody meets. That is exactly what happened while staging was down for 45 minutes (#268):
 * this script reported `ordinary browsing rejected ... 0` against a site answering Traefik's own 404
 * on every path, and only an external probe caught it.
 *
 * A dead site now fails on `rl_broken`, and the summary names the status it saw.
 */
const broken = new Counter('rl_broken')
const brokenStatus = {}

/**
 * `RESOLVE` is `host:ip`, for reaching an environment whose name does not resolve publicly.
 *
 * Staging has no public DNS record (PLATFORM_SETUP §6) and is reached over the WireGuard tunnel, so
 * without this the script fails at DNS and looks like an outage. `INSECURE` goes with it: staging's
 * certificate comes from Let's Encrypt's *staging* CA, which is deliberately not publicly trusted.
 */
function hostsOption() {
    if (!__ENV.RESOLVE) return {}
    const separator = __ENV.RESOLVE.lastIndexOf(':')
    return {[__ENV.RESOLVE.slice(0, separator)]: __ENV.RESOLVE.slice(separator + 1)}
}

export const options = {
    hosts: hostsOption(),
    insecureSkipTLSVerify: __ENV.INSECURE === 'true',
    scenarios: {
        // One VU, a handful of iterations: this is not a load test. It asks whether a single
        // visitor doing ordinary things is ever rejected, and one visitor is the whole question.
        browsing: {
            executor: 'per-vu-iterations',
            exec: 'browsing',
            vus: 1,
            iterations: Number(__ENV.VISITS || 4),
            maxDuration: '2m',
        },
        // `constant-vus`, never `constant-arrival-rate`. An arrival rate k6 cannot keep up with
        // queues VUs until the concurrency limit answers instead of the rate limit, and the run
        // then passes for the wrong reason. Bounded streams keep in-flight requests at
        // `ABUSE_STREAMS` whatever the server does.
        //
        // `startTime` is load-bearing too — see the header. The gap is far longer than
        // `burst/average` needs (250/50 = 5s), because a refill that has not finished would fail
        // `browsing`'s sibling for a reason that has nothing to do with the limit being wrong.
        abuse: {
            executor: 'constant-vus',
            exec: 'abuse',
            startTime: '2m30s',
            vus: ABUSE_STREAMS,
            duration: '15s',
        },
    },
    thresholds: {
        // The two findings. Both are absolute: there is no acceptable rate of rejecting visitors,
        // and no acceptable version of a limit that lets a flood through untouched.
        rl_rejected_browsing: ['count==0'],
        rl_rejected_abuse: ['count>0'],
        // **Read these two before either finding above.** A site that is not serving scores zero on
        // both counters, so without them the verdict on a dead site is "the limit never engaged".
        rl_broken: ['count==0'],
        checks: ['rate==1'],
    },
    // A 429 is the point of this script, so k6's default "4xx and 5xx are failures" would report a
    // successful run as a catastrophe. The counters above carry the verdict instead.
    discardResponseBodies: false,
}

/** Everything `index.html` preloads. Parsed rather than pinned: the filenames are content-hashed. */
function preloadedAssets(html) {
    return [...new Set(html.match(/\/assets\/[A-Za-z0-9._-]+/g) || [])]
}

/** Every distinct cached-image URL in an events payload — one per card is what a browser fetches. */
function imageUrls(body, limit) {
    return [...new Set(body.match(/\/api\/images\/[A-Za-z0-9/._-]+/g) || [])].slice(0, limit)
}

/**
 * Sort one response into the only three outcomes this script accepts.
 *
 * 200 is a served request, 429 is the limit doing its job, and **everything else means the run has
 * not measured what it claims to measure** — a 404 from a dropped router, a 502 from a pod that is
 * not ready, a 0 from a connection that never opened.
 */
function record(response, rejected) {
    if (response.status === 429) rejected.add(1)
    else if (response.status !== 200) {
        broken.add(1)
        brokenStatus[response.status] = (brokenStatus[response.status] || 0) + 1
    }
    return response.status
}

/**
 * One first-time visitor, in the order a browser actually issues the requests.
 *
 * Sequential rather than batched on purpose. `http.batch` would fire them all in one instant, which
 * is harsher than any real browser (six connections per origin) and would fail the run on a limit
 * that is fine. The `sleep` at the end is the visitor reading the page.
 */
export function browsing() {
    const index = http.get(`${ORIGIN}/`, {tags: {group: 'page', name: '/'}})
    record(index, browsingRejected)
    // The document is checked rather than merely counted, because a first visit that fetched no
    // HTML has no assets and no images to fetch either — so every later counter reads zero and the
    // run looks quiet instead of broken.
    check(index, {'the document is served': (r) => r.status === 200})

    const assets = preloadedAssets(index.body || '')
    check(assets, {'the document preloads its assets': (list) => list.length > 0})
    for (const asset of assets) {
        record(http.get(`${ORIGIN}${asset}`, {tags: {group: 'page', name: '/assets/*'}}), browsingRejected)
    }

    const events = http.get(`${BASE_URL}/events?size=20`, {tags: {group: 'list', name: '/events'}})
    record(events, browsingRejected)
    check(events, {'the events query is answered': (r) => r.status === 200})

    // Capped at 20 because that is a full page of cards, each rendering one `<picture>`. The real
    // browser fetches fewer — the `<img>` is `loading="lazy"` — so this is the worst case, which is
    // the only case worth setting a limit against.
    for (const image of imageUrls(events.body || '', 20)) {
        record(http.get(`${ORIGIN}${image}`, {tags: {group: 'image', name: '/api/images/*'}}), browsingRejected)
    }

    sleep(5)
}

/**
 * One source, above the rate limit and below the concurrency limit, on the cheapest endpoint here.
 *
 * No `sleep`, so each stream runs as fast as the server answers. That is what a scraper does, and
 * it is the shape `inFlightReq` is blind to: ten requests in flight is nothing, and eighty a second
 * from one address is not.
 */
export function abuse() {
    record(http.get(`${BASE_URL}/meta`, {tags: {group: 'detail', name: '/meta'}}), abuseRejected)
}

/**
 * The summary k6 prints is a wall of percentiles that says nothing about the two questions, so this
 * states the verdict in the terms the script was written in.
 */
export function handleSummary(data) {
    const count = (name) => (data.metrics[name] && data.metrics[name].values.count) || 0
    const browsingHits = count('rl_rejected_browsing')
    const abuseHits = count('rl_rejected_abuse')
    const brokenHits = count('rl_broken')
    const seen = Object.keys(brokenStatus)
        .map((status) => `${status}×${brokenStatus[status]}`)
        .join(' ')

    // Every line carries the same `| ` marker. The verdict was once lost to a `grep -v` on a word
    // one line happened to start with, and a summary that a filter can silently halve is not a
    // summary. Nothing here starts with a scenario name for the same reason.
    const lines = [
        '',
        '| Rate limit — the questions, in the order they invalidate each other',
        `|   neither 200 nor 429 .... ${brokenHits}  (must be 0)${seen ? `   saw: ${seen}` : ''}`,
        `|   visitor rejected ....... ${browsingHits}  (must be 0)`,
        `|   flood rejected ......... ${abuseHits}  (must be above 0)`,
    ]

    if (brokenHits > 0) {
        lines.push('| → MEASURED NOTHING. The site is not answering, so both counts below it are')
        lines.push('|   silence rather than evidence. Fix the environment, then re-run.')
    } else if (browsingHits > 0) {
        lines.push('| → TOO TIGHT. A visitor is being rejected; raise burst before anything else.')
    } else if (abuseHits === 0) {
        lines.push('| → NOT ENGAGING. Nothing was limited, which is indistinguishable from no limit.')
    } else {
        lines.push('| → The limit engages, and a visitor never meets it.')
    }

    return {stdout: `${lines.join('\n')}\n\n`}
}
