/**
 * Prints a k6 summary export on one line, without `setup_data`.
 *
 * The smoke hook (#1697) runs this after `smoke.js`, so each reconcile leaves one JSON row in
 * OpenObserve. `setup_data` holds the discovered slugs, artist names among them. k6 writes the
 * export's top-level keys in a random order, so only a JSON parse can remove that key (#2392).
 *
 *   k6 run --quiet -e SUMMARY_EXPORT=/tmp/summary.json perf/lib/summary-line.js
 */
const summary = JSON.parse(open(__ENV.SUMMARY_EXPORT))
delete summary.setup_data

export const options = {vus: 1, iterations: 1}

export default function () {}

export function handleSummary() {
    return {stdout: `${JSON.stringify(summary)}\n`}
}
