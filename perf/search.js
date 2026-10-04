/**
 * Search test — the header's type-ahead under many visitors at once (#2533).
 *
 * The SPA does not send a request per keystroke. `useGlobalSearch.ts` waits 250 ms after the last
 * key, and a new key cancels the wait and the request in flight. So a visitor typing at about
 * 150 ms a key sends one request per pause and one at the end of the word. A script that sent every
 * prefix would overstate the load about threefold, which is what the first measurement did.
 *
 * `/api/search` bypasses `ResponseCache`, so this is the one script whose numbers are the database's
 * rather than the cache's.
 *
 *   k6 run perf/search.js
 *   k6 run -e VUS=50 perf/search.js
 */
import {check, sleep} from 'k6'
import http from 'k6/http'

import {BASE_URL, baseThresholds} from './lib/config.js'
import {api, discover} from './lib/api.js'

const VUS = Number(__ENV.VUS || 20)
const KEY_MS = 150
const DEBOUNCE_MS = 250

export const options = {
    scenarios: {
        typing: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                {duration: __ENV.RAMP_UP || '30s', target: VUS},
                {duration: __ENV.DURATION || '2m', target: VUS},
                {duration: __ENV.RAMP_DOWN || '15s', target: 0},
            ],
            gracefulRampDown: '15s',
        },
    },
    thresholds: {
        http_req_failed: baseThresholds().http_req_failed,
        // Staging, 2026-10-04: worst p95 280 ms over six runs, times 1.5 (perf/README.md).
        'http_req_duration{group:search}': [`p(95)<${Number(__ENV.THRESHOLD_SEARCH_MS || 450)}`],
    },
}

/** Words a visitor would type: four letters or more, from the names the site actually lists. */
export function setup() {
    discover()
    const words = new Set()
    for (const path of ['/venues?size=100', '/events?size=100', '/artists?size=100', '/promoters?size=100']) {
        const response = http.get(`${BASE_URL}${path}`, {tags: {name: 'setup'}})
        if (response.status !== 200) continue
        for (const item of response.json('content') || []) {
            for (const word of String(item.name || item.title || '').split(/[\s,.:;!?()"'/&+-]+/)) {
                if (word.length >= 4) words.add(word.toLowerCase())
            }
        }
    }
    return {words: [...words]}
}

/** Swaps two neighbouring letters, the commonest typo, which the BFF's similarity pass forgives. */
function transpose(word) {
    const at = 1 + Math.floor(Math.random() * (word.length - 2))
    return word.slice(0, at) + word[at + 1] + word[at] + word.slice(at + 2)
}

function send(q, limit, label) {
    check(api.search(q, limit), {[`${label}: status is 200`]: (r) => r.status === 200})
}

export default function (data) {
    const original = data.words[Math.floor(Math.random() * data.words.length)]
    const word = Math.random() < 0.2 ? transpose(original) : original

    for (let typed = 1; typed <= word.length; typed++) {
        sleep((KEY_MS - 50 + Math.random() * 100) / 1000)
        const pauses = typed < word.length && Math.random() < 0.25
        if (pauses) {
            sleep((DEBOUNCE_MS + 50 + Math.random() * 400) / 1000)
            if (typed >= 2) send(word.slice(0, typed), 5, 'GET /search (pause)')
        }
    }
    sleep((DEBOUNCE_MS + 50) / 1000)
    send(word, 5, 'GET /search (word)')

    // One visitor in four presses Enter and opens the search page.
    if (Math.random() < 0.25) send(word, 20, 'GET /search (page)')

    sleep(3 + Math.random() * 5)
}
