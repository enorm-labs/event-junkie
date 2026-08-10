/**
 * Shared configuration for the k6 suite.
 *
 * Every knob that a runner might want to change is an environment variable with a default that
 * works against a local `scripts/dev-env.sh up bff`, so the common case is `k6 run perf/smoke.js`
 * with no arguments.
 */

/**
 * The BFF's origin. No `/api` prefix: that prefix is a *frontend* concern — the Vite dev server
 * strips it before proxying (see events-frontend/vite.config.ts). The BFF itself serves
 * `/events`, `/venues`, … at the root, and pointing k6 at `/api/...` is the first mistake
 * everybody makes here.
 */
export const BASE_URL = (__ENV.BFF_HOST || 'http://localhost:8080').replace(/\/$/, '')

/**
 * Latency budgets, in milliseconds, for a **local** run against a laptop.
 *
 * These are deliberately not "production SLOs" — there is no production yet (ADR-012 picked the
 * platform, but nothing is provisioned). They are regression detectors: numbers loose enough that
 * an ordinary laptop under an ordinary background load does not trip them, and tight enough that
 * an accidental N+1 query or a dropped index does. Re-baseline them against real infrastructure
 * once something is deployed, and treat that as a deliberate act rather than raising them whenever
 * a run goes red.
 */
export const THRESHOLD_MS = {
    /** Single-row lookups by slug. Indexed, small payload; anything else is a regression. */
    detail: Number(__ENV.THRESHOLD_DETAIL_MS || 300),
    /** Paged list endpoints. A join and a count, so meaningfully slower than a detail read. */
    list: Number(__ENV.THRESHOLD_LIST_MS || 600),
    /**
     * The calendar range query. The heaviest read in the API: up to 92 days of events with their
     * venues, unpaged, in one response.
     */
    calendar: Number(__ENV.THRESHOLD_CALENDAR_MS || 1200),
}

/**
 * Thresholds shared by every scenario.
 *
 * `http_req_failed` is the one that matters most and the one most easily fudged: a load test that
 * reports a beautiful p95 while quietly 500-ing a tenth of its requests is worse than no test.
 * Anything above 1% fails the run.
 */
export function baseThresholds() {
    return {
        http_req_failed: ['rate<0.01'],
        checks: ['rate>0.99'],
        // Tagged per endpoint group in endpoints.js, so a slow calendar query cannot hide behind fast
        // detail reads in an aggregate p95 — which is exactly what an overall threshold would let it do.
        'http_req_duration{group:detail}': [`p(95)<${THRESHOLD_MS.detail}`],
        'http_req_duration{group:list}': [`p(95)<${THRESHOLD_MS.list}`],
        'http_req_duration{group:calendar}': [`p(95)<${THRESHOLD_MS.calendar}`],
    }
}

/** ISO date `days` from today, as the API expects it (`YYYY-MM-DD`). */
export function isoDate(days = 0) {
    const date = new Date()
    date.setDate(date.getDate() + days)
    return date.toISOString().slice(0, 10)
}
