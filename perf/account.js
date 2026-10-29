/**
 * Account test — signed-in visitors, with the seeded sessions of `fixtures/accounts.sql` (#4104).
 *
 * Every route here skips `ResponseCache` and looks the session up in PostgreSQL, so this is the load
 * the account features added and `load.js` never sends. The fixture accounts exist only on k3d and a
 * local stack: never point this at staging or production, where the tokens sign in nobody.
 *
 *   k6 run -e BFF_HOST=http://event-junkie.localhost:8080 -e RESOLVE=event-junkie.localhost:127.0.0.1:8080 perf/account.js
 */
import {check, fail, group, sleep} from 'k6'
import http from 'k6/http'

import {BASE_URL, baseThresholds, hostsOption} from './lib/config.js'
import {checkOk, checkPage, discover, me, pick, savedSearchCount, signedIn} from './lib/api.js'

const VUS = Number(__ENV.VUS || 5)
const ACCOUNTS = JSON.parse(open('../fixtures/accounts.json')).accounts

export const options = {
    hosts: hostsOption(),
    scenarios: {
        signedIn: {
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
        ...baseThresholds(),
        // k3d, 2026-10-29: worst p95 21 ms over three runs, times 1.5 (perf/README.md).
        'http_req_duration{group:account}': [`p(95)<${Number(__ENV.THRESHOLD_ACCOUNT_MS || 50)}`],
    },
}

/** A 401 is cheap, so a run whose tokens sign in nobody would report a fast, healthy p95. */
export function setup() {
    const data = discover()
    for (const account of ACCOUNTS) {
        const response = http.get(`${BASE_URL}/auth/session`, {...signedIn(account), tags: {name: 'setup'}})
        const email = response.status === 200 ? response.json('account.email') : null
        if (email !== account.email) {
            fail(`The fixture session for ${account.email} signs in as ${email}. Load fixtures/accounts.sql first.`)
        }
    }
    return data
}

/**
 * What the SPA asks as a signed-in page loads: the header's session and bell, then the Following page.
 * Returns what the account follows and has marked, which the writes must leave alone.
 */
function visitFollowing(account) {
    const seeded = {venues: [], events: []}
    group('following', () => {
        checkOk(me.session(account), 'GET /auth/session (signed in)')
        checkOk(me.unreadCount(account), 'GET /me/notifications/unread-count')
        const follows = me.follows(account)
        if (checkOk(follows, 'GET /me/follows')) {
            seeded.venues = follows.json().filter((f) => f.kind === 'venue').map((f) => f.slug)
        }
        checkOk(me.following(account), 'GET /me/following')
        checkOk(me.followingToday(account), 'GET /me/following/today')
        const searches = me.searches(account)
        checkOk(searches, 'GET /me/searches')
        for (const search of searches.status === 200 ? searches.json() : []) {
            checkPage(savedSearchCount(search.query), 'GET /events (saved search count)')
        }
        const rsvps = me.rsvps(account)
        if (checkOk(rsvps, 'GET /me/rsvps')) seeded.events = Object.keys(rsvps.json())
    })
    return seeded
}

/** Follow and unfollow a venue, then mark an event and take it back, so the account's rows end as they began. */
function changeSomething(account, data, seeded) {
    const venue = pick(data.venues.filter((slug) => !seeded.venues.includes(slug)))
    const event = pick(data.events.filter((slug) => !seeded.events.includes(slug)))
    group('writes', () => {
        if (venue) {
            check(me.follow(account, venue), {'PUT /me/follows: 204': (r) => r.status === 204})
            check(me.unfollow(account, venue), {'DELETE /me/follows: 204': (r) => r.status === 204})
        }
        if (event) {
            check(me.rsvp(account, event), {'PUT /me/rsvps: 204': (r) => r.status === 204})
            check(me.unrsvp(account, event), {'DELETE /me/rsvps: 204': (r) => r.status === 204})
        }
    })
}

export default function (data) {
    const account = ACCOUNTS[__VU % ACCOUNTS.length]
    const seeded = visitFollowing(account)
    if (Math.random() < 0.2) changeSomething(account, data, seeded)
    sleep(1 + Math.random() * 2)
}
