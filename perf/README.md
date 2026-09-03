# Performance tests (k6)

Load and performance tests for the **BFF's public read API**, written for [k6](https://k6.io).

```bash
brew install k6                       # macOS
scripts/dev-env.sh up bff             # the tests need something to talk to

k6 run perf/smoke.js                  # every endpoint once — is it all still working?
k6 run perf/load.js                   # sustained realistic load — does latency stay flat?
k6 run perf/spike.js                  # a sudden surge — does it recover?
k6 run perf/ratelimit.js              # the ingress limit — deployed environments only, see below
```

## What each script is for

They answer different questions, and reading one's output as if it were another's is the main way to draw a wrong conclusion from them.

| Script         | Shape                     | Question it answers                                                                  |
| -------------- | ------------------------- | ------------------------------------------------------------------------------------ |
| `smoke.js`     | 1 VU, 1 iteration         | Does every endpoint still work, and is anything catastrophically slow?               |
| `load.js`      | ramp to N VUs, hold       | Does latency stay flat as concurrency rises, or does something serialise?            |
| `spike.js`     | quiet → surge → quiet     | Does it survive a sudden crowd, and — more importantly — does it recover afterwards? |
| `ratelimit.js` | one visitor, then a flood | Does the per-source limit stop abuse without ever rejecting a visitor?               |

**`smoke.js` is the one to reach for by default.** It puts no meaningful load on anything, finishes in about a second, and tolerates an empty database, so it is
safe to run anywhere at any time. Use it after a dependency bump, after a query change, or to check an environment is alive.

**`load.js` is where the interesting failure lives.** The number to watch is not requests per second — it is whether p95 climbs with the VU count. A curve that
rises means something is serialising: an exhausted R2DBC connection pool, a blocking call on the event loop, a query without an index. WebFlux hides all three
well until it doesn't, which is precisely why this test exists.

**`ratelimit.js` measures the middleware, not the application**, so it is the one script that says nothing at all against a laptop. It exercises
`ingress.rateLimit.perSource` (#268) and fails a run two ways: a 429 during ordinary browsing, and **no** 429 under abuse. Either alone is worthless — a limit
nobody meets and a limit that never engages look identical from the outside. Its unit is a whole page view, images and fonts included, because one Ingress
carries the site and they all spend the same budget.

```bash
k6 run -e BFF_HOST=https://staging.event-junkie.de \
       -e RESOLVE=staging.event-junkie.de:10.10.1.1 -e INSECURE=true perf/ratelimit.js
```

`RESOLVE` and `INSECURE` are what reach staging: it has no public DNS record (PLATFORM_SETUP §6) and its certificate comes from Let's Encrypt's _staging_ CA,
which is deliberately not publicly trusted. **The two scenarios run in sequence and must stay that way** — they share this machine's address, and therefore one
token bucket.

**The abuse scenario is deliberately low-concurrency, and that is what makes it a test.** Traefik answers `inFlightReq` and `rateLimit` with the same bare 429,
and nothing in the response says which fired. Measured on staging with `perSource.enabled: false`: 300 parallel requests produced 66 rejections — all of them
from the concurrency limit. A scenario shaped like that reports a healthy pass against a per-source limit that is switched off. Ten sequential streams push
about 80 requests a second and never approach `inFlightRequests: 100`, so a 429 there has only one possible source. The same 400 requests against the disabled
limit produced zero.

**`spike.js` matches how traffic to an events site actually arrives.** A lineup announcement or a festival going on sale sends a lot of people to the _same_ few
pages within minutes, then it stops. Errors _during_ the spike are survivable; errors that continue _after_ it are the real finding — that is a pool that never
drained or a queue that never emptied. Its thresholds are off by default for that reason: a red threshold would only tell you that a spike is hard, which was
never in question. `-e STRICT=true` turns them on.

## Configuration

Everything is an environment variable with a working default:

| Variable                | Default                 | Notes                                                                                                                                                                  |
| ----------------------- | ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `BFF_HOST`              | `http://localhost:8080` | **An origin, not a path.** The scripts append `/api` themselves, because the BFF serves that prefix everywhere — see below. Adding it here asks for `/api/api/events`. |
| `VUS`                   | `20`                    | `load.js` — peak virtual users                                                                                                                                         |
| `DURATION`              | `2m`                    | `load.js` — how long to hold the peak                                                                                                                                  |
| `PEAK`                  | `100`                   | `spike.js` — peak virtual users                                                                                                                                        |
| `STRICT`                | unset                   | `spike.js` — apply the standard thresholds                                                                                                                             |
| `RESOLVE`               | unset                   | `ratelimit.js` — `host:ip`, for an environment whose name does not resolve publicly                                                                                    |
| `INSECURE`              | unset                   | `ratelimit.js` — accept a certificate from a CA that is not publicly trusted                                                                                           |
| `VISITS`                | `4`                     | `ratelimit.js` — first-time page loads the browsing scenario performs                                                                                                  |
| `ABUSE_STREAMS`         | `10`                    | `ratelimit.js` — concurrent streams in the abuse scenario; must stay under `inFlightRequests`                                                                          |
| `THRESHOLD_DETAIL_MS`   | `300`                   | p95 budget for single-row lookups                                                                                                                                      |
| `THRESHOLD_LIST_MS`     | `600`                   | p95 budget for paged list endpoints                                                                                                                                    |
| `THRESHOLD_CALENDAR_MS` | `1200`                  | p95 budget for the calendar range query — the heaviest read in the API                                                                                                 |

```bash
k6 run -e VUS=50 -e DURATION=5m perf/load.js
k6 run -e BFF_HOST=https://staging.example.invalid perf/smoke.js
```

**The `/api` prefix is in the controllers**, as `@RequestMapping("/api/events")` and its siblings —
not in an ingress rewrite, and not in `spring.webflux.base-path`, which nothing sets. So it is there
under `bootRun` and in a cluster alike, and `lib/config.js` appends it once rather than every script
carrying it.

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
