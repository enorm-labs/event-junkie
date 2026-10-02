/**
 * Load test — a sustained, realistically-shaped browsing load.
 *
 * The point is not the peak number of requests per second. It is whether latency stays flat while
 * concurrency rises: a curve that climbs with the VU count means something is serialising —
 * an exhausted R2DBC connection pool, a blocking call on the event loop, a query without an index.
 * That is the failure this project is most exposed to, because WebFlux hides it well until it
 * doesn't.
 *
 * The mix mirrors how the site is actually used rather than hitting endpoints uniformly: home and
 * the events list carry most sessions, a visitor from outside lands on one event, and the
 * calendar and venues are minor. Uniform traffic would spend most of its budget on the endpoints
 * nobody calls and report a p95 that means nothing.
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

/** The SPA's first request: build info for the footer, once per app start. */
function startApp() {
    checkOk(api.meta(), 'GET /meta')
}

/** The filter bar on the events list and the calendar loads both dropdowns as it mounts. */
function loadFilterBar() {
    checkOk(api.genres(), 'GET /genres')
    checkPage(api.listVenues('?page=0&size=100'), 'GET /venues (filter bar)')
}

/** Land on the home page: two independent feeds, fired together on mount. */
function visitHome() {
    group('home', () => {
        checkOk(api.today(), 'GET /events/today')
        checkPage(api.searchEvents('?size=12'), 'GET /events (upcoming feed)')
    })
}

/** Open one event from a feed or a list. */
function openEvent(data) {
    const event = pick(data.events)
    if (event) group('event detail', () => checkOk(api.event(event), 'GET /events/{slug}'))
}

/**
 * Arrive on an event's own page from a link outside the site. nginx asks the injector for the
 * page's head, and the injector reads the event once; the SPA then reads it again as it mounts.
 */
function landOnEvent(data) {
    const event = pick(data.events)
    if (!event) return
    group('event detail', () => {
        checkOk(api.event(event), 'GET /events/{slug} (injector)')
        checkOk(api.event(event), 'GET /events/{slug}')
    })
}

/** Browse the events list, narrow it, then open something. */
function browseEvents(data) {
    group('events list', () => {
        checkPage(api.searchEvents('?size=20'), 'GET /events')
        loadFilterBar()

        // A filtered follow-up, as a visitor narrowing results would produce.
        const venue = pick(data.venues)
        if (venue) checkPage(api.searchEvents(`?venue=${venue}&size=20`), 'GET /events?venue=')
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
        loadFilterBar()
    })
}

export default function (data) {
    // The weights come from one week of production traffic, 2026-09-25 to 2026-10-02 (#297).
    // Each page has one request only it makes on mount, so counting that request counts the
    // page: /events/today for home, /events/calendar for the calendar, and /genres for the
    // events list plus the calendar. Event-detail landings are human page loads in the nginx log,
    // because the injector and Baiduspider swamp the detail endpoint. perf/README.md has the queries.
    const roll = Math.random()

    startApp()
    if (roll < 0.3) {
        visitHome()
        sleep(1)
        openEvent(data)
    } else if (roll < 0.65) {
        browseEvents(data)
        sleep(1)
        openEvent(data)
    } else if (roll < 0.8) landOnEvent(data)
    else if (roll < 0.9) openCalendar()
    else browseVenues(data)

    // Think time. Without it a VU is a tight loop, which measures how fast the client can spin
    // rather than how the service behaves under a given number of concurrent *users*.
    sleep(Math.random() * 3 + 1)
}
