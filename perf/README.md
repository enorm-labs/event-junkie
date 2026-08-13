# Performance tests (k6)

Load and performance tests for the **BFF's public read API**, written for [k6](https://k6.io).

```bash
brew install k6                       # macOS
scripts/dev-env.sh up bff             # the tests need something to talk to

k6 run perf/smoke.js                  # every endpoint once — is it all still working?
k6 run perf/load.js                   # sustained realistic load — does latency stay flat?
k6 run perf/spike.js                  # a sudden surge — does it recover?
```

## What each script is for

They answer different questions, and reading one's output as if it were another's is the main way to draw a wrong conclusion from them.

| Script     | Shape                 | Question it answers                                                                  |
| ---------- | --------------------- | ------------------------------------------------------------------------------------ |
| `smoke.js` | 1 VU, 1 iteration     | Does every endpoint still work, and is anything catastrophically slow?               |
| `load.js`  | ramp to N VUs, hold   | Does latency stay flat as concurrency rises, or does something serialise?            |
| `spike.js` | quiet → surge → quiet | Does it survive a sudden crowd, and — more importantly — does it recover afterwards? |

**`smoke.js` is the one to reach for by default.** It puts no meaningful load on anything, finishes in about a second, and tolerates an empty database, so it is
safe to run anywhere at any time. Use it after a dependency bump, after a query change, or to check an environment is alive.

**`load.js` is where the interesting failure lives.** The number to watch is not requests per second — it is whether p95 climbs with the VU count. A curve that
rises means something is serialising: an exhausted R2DBC connection pool, a blocking call on the event loop, a query without an index. WebFlux hides all three
well until it doesn't, which is precisely why this test exists.

**`spike.js` matches how traffic to an events site actually arrives.** A lineup announcement or a festival going on sale sends a lot of people to the _same_ few
pages within minutes, then it stops. Errors _during_ the spike are survivable; errors that continue _after_ it are the real finding — that is a pool that never
drained or a queue that never emptied. Its thresholds are off by default for that reason: a red threshold would only tell you that a spike is hard, which was
never in question. `-e STRICT=true` turns them on.

## Configuration

Everything is an environment variable with a working default:

| Variable                | Default                 | Notes                                                                                                                                                        |
| ----------------------- | ----------------------- | ------------------------------------------------------------------------------------------------------------------------------------------------------------ |
| `BFF_HOST`              | `http://localhost:8080` | **No `/api` prefix.** That prefix is a frontend concern — the Vite dev server strips it before proxying. The BFF serves `/events`, `/venues`, … at the root. |
| `VUS`                   | `20`                    | `load.js` — peak virtual users                                                                                                                               |
| `DURATION`              | `2m`                    | `load.js` — how long to hold the peak                                                                                                                        |
| `PEAK`                  | `100`                   | `spike.js` — peak virtual users                                                                                                                              |
| `STRICT`                | unset                   | `spike.js` — apply the standard thresholds                                                                                                                   |
| `THRESHOLD_DETAIL_MS`   | `300`                   | p95 budget for single-row lookups                                                                                                                            |
| `THRESHOLD_LIST_MS`     | `600`                   | p95 budget for paged list endpoints                                                                                                                          |
| `THRESHOLD_CALENDAR_MS` | `1200`                  | p95 budget for the calendar range query — the heaviest read in the API                                                                                       |

```bash
k6 run -e VUS=50 -e DURATION=5m perf/load.js
k6 run -e BFF_HOST=https://staging.example.invalid perf/smoke.js
```

## Two things that keep these honest

**Slugs are discovered, never hard-coded.** Each script's `setup()` calls the list endpoints and hands the resulting slugs to every VU. Hard-coded slugs would
be wrong within a week — seed data changes, events fall into the past and get dropped — and they would fail in the worst possible way:
a run that 404s every detail request still reports a fast, healthy-looking p95, because a 404 is cheap. `discover()` also fails loudly when the BFF is
unreachable or the database is empty, rather than measuring nothing successfully.

**Thresholds are tagged per endpoint group.** An overall p95 lets a slow calendar query hide behind a hundred fast detail reads. `detail`, `list` and `calendar`
each carry their own budget.

## Where the numbers come from

The defaults are calibrated for a **local run against a laptop**, with the dev database's seeded data. They are regression detectors, not SLOs: loose enough
that an ordinary machine under ordinary background load does not trip them, tight enough that an accidental N+1 or a dropped index does.

There is no production environment yet — [ADR-012](../docs/adr/ADR-012_CLOUD_PLATFORM.md) is still Proposed. **Re-baseline against real infrastructure once
something is deployed**, and treat that as a deliberate act. Raising a threshold because a run went red is how a performance suite becomes decorative.

## Why there is no CI workflow (yet)

Considered, and deliberately not added. Three reasons, each of which is also the condition under which the answer changes:

1. **There is nothing representative to run it against.** Nothing is deployed. Numbers from a shared GitHub runner — noisy neighbours, no dedicated CPU,
   variance of several hundred percent between runs — are not a baseline, and a threshold set loosely enough to survive them catches nothing. → _Add it once a
   staging environment exists (ADR-012), pointed at that._
2. **The functional coverage is already there and is better.** A CI run would need Postgres, then the importer to apply the Flyway migrations (the BFF owns
   none), then the BFF — and would end up asserting that every endpoint returns 200 against an **empty** database. The Testcontainers integration tests already
   do that with real data, in-process, on every build. → _A perf workflow should measure, not duplicate._
3. **Trend matters more than a pass/fail gate.** A single red build tells you almost nothing about performance; a p95 that has drifted 40% over two months tells
   you a lot. That wants results stored over time (k6 Cloud, or Prometheus remote-write into the monitoring stack ADR-012 already calls for), not a threshold in
   a workflow. → _Wire it into monitoring when monitoring exists._

Until then these run on demand, locally, against a real database. Tracked in
[issues #297 and #298](https://github.com/enorm-labs/event-junkie/issues/298).

## Adding a scenario

Endpoints live in [`lib/api.js`](lib/api.js) — one place to follow when a controller changes. Thresholds and shared options live in [
`lib/config.js`](lib/config.js). A new script should reuse both rather than issuing bare `http.get` calls, so it inherits the tagging that makes the per-group
thresholds work.
