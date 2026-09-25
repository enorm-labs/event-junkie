/**
 * The BFF's public read API, as k6 sees it: request helpers, response checks, and the discovery
 * step that keeps the scripts from hard-coding slugs.
 *
 * Endpoints mirror the controllers under `events-bff/src/main/kotlin/de/norm/events/`. When one
 * changes, this file is the single place to follow it.
 */
import http from 'k6/http'
import {check, fail, sleep} from 'k6'

import {BASE_URL, ORIGIN, WAIT_FOR_ORIGIN_SECONDS, isoDate} from './config.js'

/**
 * A tagged GET.
 *
 * The `group` tag is what makes per-endpoint thresholds possible (see `baseThresholds`), and
 * `name` collapses `/events/some-slug` and `/events/another-slug` into one row in the summary
 * instead of one row per slug — k6's URL grouping does not happen automatically for path
 * parameters.
 */
export function get(path, {group, name, params = {}} = {}) {
    return http.get(`${BASE_URL}${path}`, {
        tags: {group, name: name || path},
        ...params,
    })
}

/** A 200 carrying a JSON body — the bar every read endpoint has to clear. */
export function checkOk(response, label) {
    return check(response, {
        [`${label}: status is 200`]: (r) => r.status === 200,
        [`${label}: body is JSON`]: (r) => (r.headers['Content-Type'] || '').includes('application/json'),
    })
}

/** As above, plus the paged envelope the list endpoints return. */
export function checkPage(response, label) {
    const ok = checkOk(response, label)
    return (
        check(response, {
            [`${label}: has a content array`]: (r) => {
                try {
                    return Array.isArray(r.json('content'))
                } catch {
                    return false
                }
            },
        }) && ok
    )
}

// --- The endpoints -----------------------------------------------------------------------------

export const api = {
    /** `GET /events?…` — the search endpoint behind the events list page. */
    searchEvents: (query = '') => get(`/events${query}`, {group: 'list', name: '/events'}),

    /** `GET /events/today` — the home page's "Tonight" feed. */
    today: () => get('/events/today', {group: 'list', name: '/events/today'}),

    /** `GET /events/calendar?from=&to=` — the calendar view. The range is capped at 92 days. */
    calendar: (fromDays = 0, toDays = 30) =>
        get(`/events/calendar?from=${isoDate(fromDays)}&to=${isoDate(toDays)}`, {
            group: 'calendar',
            name: '/events/calendar',
        }),

    event: (slug) => get(`/events/${slug}`, {group: 'detail', name: '/events/{slug}'}),

    listVenues: (query = '') => get(`/venues${query}`, {group: 'list', name: '/venues'}),
    venue: (slug) => get(`/venues/${slug}`, {group: 'detail', name: '/venues/{slug}'}),

    listArtists: (query = '') => get(`/artists${query}`, {group: 'list', name: '/artists'}),
    artist: (slug) => get(`/artists/${slug}`, {group: 'detail', name: '/artists/{slug}'}),

    listPromoters: (query = '') => get(`/promoters${query}`, {group: 'list', name: '/promoters'}),
    promoter: (slug) => get(`/promoters/${slug}`, {group: 'detail', name: '/promoters/{slug}'}),

    /** `GET /genres` — an unpaged list, used to populate the filter bar's dropdown. */
    genres: () => get('/genres', {group: 'list', name: '/genres'}),

    /** `GET /meta` — build info for the footer. Cheap, and the best liveness probe here. */
    meta: () => get('/meta', {group: 'detail', name: '/meta'}),
}

// --- Discovery ---------------------------------------------------------------------------------

/** Every status counts as expected, the failed dial (`0`) included, so a wait is not a failure. */
const WAITING = http.expectedStatuses(0, {min: 200, max: 599})

/**
 * Polls `GET /meta` until it answers 200 or {@link WAIT_FOR_ORIGIN_SECONDS} runs out. The polls
 * stay out of `http_req_failed`; the request after them counts as usual, so an origin that never
 * comes up still fails the run.
 */
function waitForOrigin() {
    const deadline = Date.now() + WAIT_FOR_ORIGIN_SECONDS * 1000
    for (let attempt = 1; Date.now() < deadline; attempt++) {
        const response = http.get(`${BASE_URL}/meta`, {responseCallback: WAITING, tags: {name: 'wait for origin'}})
        if (response.status === 200) return
        console.log(`waiting for ${ORIGIN}: attempt ${attempt}, status ${response.status}${response.error ? `, ${response.error}` : ''}`)
        sleep(5)
    }
}

/**
 * Ask the API what exists, so the scripts exercise real rows instead of invented ones.
 *
 * Hard-coded slugs would be wrong within a week: the seed data changes, events fall into the past
 * and are dropped, venues are added. Worse, they would fail *quietly useful* — a run that 404s
 * every detail request still produces a fast, healthy-looking p95, because a 404 is cheap.
 *
 * Called from each script's `setup()`, which k6 runs exactly once; the returned object is handed
 * to every VU.
 */
export function discover({requireData = true} = {}) {
    waitForOrigin()
    const liveness = api.meta()
    if (liveness.status !== 200) {
        fail(
            `The BFF is not answering at ${BASE_URL} (GET /meta → ${liveness.status}). ` +
            'Start it with `scripts/dev-env.sh up bff`, or point k6 elsewhere with `-e BFF_HOST=…`.',
        )
    }

    const slugsFrom = (response, path) => {
        if (response.status !== 200) return []
        try {
            const body = response.json(path)
            return (body || []).map((item) => item.slug).filter(Boolean)
        } catch {
            return []
        }
    }

    const data = {
        events: slugsFrom(api.searchEvents('?size=20'), 'content'),
        venues: slugsFrom(api.listVenues('?size=20'), 'content'),
        artists: slugsFrom(api.listArtists('?size=20'), 'content'),
        promoters: slugsFrom(api.listPromoters('?size=20'), 'content'),
    }

    const total = Object.values(data).reduce((sum, list) => sum + list.length, 0)
    if (total === 0) {
        const message =
            `The BFF at ${BASE_URL} answered, but the database is empty — every list endpoint ` +
            'returned no rows. Seed it with `scripts/dev-env.sh seed-all` and run an import, or the ' +
            'detail endpoints below are measuring 404s.'
        if (requireData) fail(message)
        console.warn(`${message} Continuing with the list endpoints only.`)
    }

    // `/events` defaults to today onwards, so an empty result can simply mean the seeded data has
    // aged out rather than that the database is empty. Worth saying plainly, because the symptom
    // (detail requests skipped) looks identical to a broken endpoint.
    if (data.events.length === 0 && data.venues.length > 0) {
        console.warn(
            'No upcoming events found — `GET /events` defaults to today onwards, so the seeded events ' +
            'may all be in the past. Event detail requests will be skipped.',
        )
    }

    return data
}

/** Pick one at random, or `null` when the list is empty. VUs must handle the empty case. */
export function pick(list) {
    if (!list || list.length === 0) return null
    return list[Math.floor(Math.random() * list.length)]
}
