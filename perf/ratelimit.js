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
 *     whole issue was filed against.
 *
 * **The scenarios run in sequence, never together**, because they share a source address — this
 * machine's — and therefore one token bucket. `abuse` starts after `browsing` has finished and the
 * bucket has had time to refill; overlapping them would fail `browsing` for the obvious wrong
 * reason. Do not "speed it up" by removing `startTime`.
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

/** How hard the abuse scenario pushes, in requests per second. Far above any human. */
const ABUSE_RATE = Number(__ENV.ABUSE_RATE || 200)

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
        // `startTime` is load-bearing — see the header. The gap is far longer than `burst/average`
        // needs (250/50 = 5s), because a refill that has not finished would fail `browsing`'s
        // sibling for a reason that has nothing to do with the limit being wrong.
        abuse: {
            executor: 'constant-arrival-rate',
            exec: 'abuse',
            startTime: '2m30s',
            rate: ABUSE_RATE,
            timeUnit: '1s',
            duration: '10s',
            preAllocatedVUs: 50,
            maxVUs: 200,
        },
    },
    thresholds: {
        // The two findings. Both are absolute: there is no acceptable rate of rejecting visitors,
        // and no acceptable version of a limit that lets a flood through untouched.
        rl_rejected_browsing: ['count==0'],
        rl_rejected_abuse: ['count>0'],
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

function countRejections(response, counter) {
    if (response.status === 429) counter.add(1)
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
    countRejections(index, browsingRejected)
    check(index, {'document is served': (r) => r.status === 200})

    for (const asset of preloadedAssets(index.body || '')) {
        countRejections(http.get(`${ORIGIN}${asset}`, {tags: {group: 'page', name: '/assets/*'}}), browsingRejected)
    }

    const events = http.get(`${BASE_URL}/events?size=20`, {tags: {group: 'list', name: '/events'}})
    countRejections(events, browsingRejected)

    // Capped at 20 because that is a full page of cards, each rendering one `<picture>`. The real
    // browser fetches fewer — the `<img>` is `loading="lazy"` — so this is the worst case, which is
    // the only case worth setting a limit against.
    for (const image of imageUrls(events.body || '', 20)) {
        countRejections(http.get(`${ORIGIN}${image}`, {tags: {group: 'image', name: '/api/images/*'}}), browsingRejected)
    }

    check(browsingRejected, {'no request was rejected during ordinary browsing': () => true})
    sleep(5)
}

/** One source, far above the limit, on the cheapest endpoint it can find. */
export function abuse() {
    countRejections(http.get(`${BASE_URL}/meta`, {tags: {group: 'detail', name: '/meta'}}), abuseRejected)
}

/**
 * The summary k6 prints is a wall of percentiles that says nothing about the two questions, so this
 * states the verdict in the terms the script was written in.
 */
export function handleSummary(data) {
    const count = (name) => (data.metrics[name] && data.metrics[name].values.count) || 0
    const browsingHits = count('rl_rejected_browsing')
    const abuseHits = count('rl_rejected_abuse')
    const verdict = [
        '',
        '  Rate limit — the two questions',
        `    ordinary browsing rejected ... ${browsingHits}  (must be 0)`,
        `    abuse rejected ............... ${abuseHits}  (must be above 0)`,
        browsingHits === 0 && abuseHits > 0
            ? '    → the limit engages, and a visitor never meets it.'
            : browsingHits > 0
              ? '    → TOO TIGHT. A visitor is being rejected; raise burst before anything else.'
              : '    → NOT ENGAGING. Nothing was limited, so this is indistinguishable from no limit.',
        '',
    ].join('\n')
    return {stdout: verdict}
}
