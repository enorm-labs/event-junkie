/**
 * Load test — a sustained, realistically-shaped browsing load.
 *
 * The point is not the peak number of requests per second. It is whether latency stays flat while
 * concurrency rises: a curve that climbs with the VU count means something is serialising —
 * an exhausted R2DBC connection pool, a blocking call on the event loop, a query without an index.
 * That is the failure this project is most exposed to, because WebFlux hides it well until it
 * doesn't.
 *
 * The mix mirrors how the site is actually used rather than hitting endpoints uniformly. Most
 * visitors land, look at what's on, open one or two things, and leave; almost nobody enumerates
 * promoters. Uniform traffic would spend most of its budget on the endpoints nobody calls and
 * report a p95 that means nothing.
 *
 *   k6 run perf/load.js
 *   k6 run -e VUS=50 -e DURATION=5m perf/load.js
 */
import {group, sleep} from 'k6'

import {baseThresholds} from './lib/config.js'
import {api, checkOk, checkPage, discover, pick} from './lib/api.js'

const VUS = Number(__ENV.VUS || 20)

export const options = {
    scenarios: {
        browsing: {
            executor: 'ramping-vus',
            startVUs: 0,
            stages: [
                // Ramp rather than jump: an instant full load measures JIT warm-up and connection-pool
                // creation, not steady-state behaviour, and both are irrelevant to a long-running service.
                {duration: __ENV.RAMP_UP || '30s', target: VUS},
                {duration: __ENV.DURATION || '2m', target: VUS},
                {duration: __ENV.RAMP_DOWN || '15s', target: 0},
            ],
            gracefulRampDown: '15s',
        },
    },
    thresholds: baseThresholds(),
}

export function setup() {
    return discover()
}

/** Land on the home page: two independent feeds, fired together on mount. */
function visitHome() {
    group('home', () => {
        checkOk(api.today(), 'GET /events/today')
        checkPage(api.searchEvents('?size=12'), 'GET /events (upcoming feed)')
    })
}

/** Browse the events list, then open something. The most common session by a wide margin. */
function browseEvents(data) {
    group('events list', () => {
        checkPage(api.searchEvents('?size=20'), 'GET /events')
        // The filter bar loads its dropdowns alongside the results.
        checkOk(api.genres(), 'GET /genres')

        // A filtered follow-up, as a visitor narrowing results would produce.
        const venue = pick(data.venues)
        if (venue) checkPage(api.searchEvents(`?venue=${venue}&size=20`), 'GET /events?venue=')

        const event = pick(data.events)
        if (event) checkOk(api.event(event), 'GET /events/{slug}')
    })
}

function browseVenues(data) {
    group('venues', () => {
        checkPage(api.listVenues('?size=24'), 'GET /venues')
        const venue = pick(data.venues)
        if (venue) {
            checkOk(api.venue(venue), 'GET /venues/{slug}')
            // The venue detail page also loads that venue's upcoming events.
            checkPage(api.searchEvents(`?venue=${venue}&size=50`), 'GET /events?venue= (detail feed)')
        }
    })
}

/** The heaviest read in the API — a month of events in a single unpaged response. */
function openCalendar() {
    group('calendar', () => {
        checkOk(api.calendar(0, 30), 'GET /events/calendar')
    })
}

export default function (data) {
    // Weights approximate a session distribution: home is the entry point, the events list is the
    // main destination, the calendar and venues are secondary. **This is a considered guess, not a
    // measurement**, and the value of a "realistic" mix depends entirely on the mix being realistic —
    // so it is tracked as issue #297 rather than left to be believed. Re-derive it from real traffic
    // once there is any.
    const roll = Math.random()

    visitHome()
    sleep(1)

    if (roll < 0.55) browseEvents(data)
    else if (roll < 0.8) openCalendar()
    else browseVenues(data)

    // Think time. Without it a VU is a tight loop, which measures how fast the client can spin
    // rather than how the service behaves under a given number of concurrent *users*.
    sleep(Math.random() * 3 + 1)
}
