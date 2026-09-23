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
`ingress.rateLimit.perSource` (#268) and fails a run three ways: **any response that is neither 200 nor 429**, a 429 during ordinary browsing, and **no** 429
under abuse. Its unit is a whole page view, images and fonts included, because one Ingress carries the site and they all spend the same budget.

**Read the three in that order, because the first invalidates the other two.** A rejection is a 429, and a site that is not routing answers 404 — so a dead
environment scores zero rejections in both scenarios and the verdict reads as a limit that never engages. That is not a hypothetical: this script reported
`ordinary browsing rejected ... 0` for the whole 45 minutes staging spent answering Traefik's own 404 on every path, and an external probe is what caught it.
`rl_broken` now fails that run and names the status it saw.

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
| `RESOLVE`               | unset                   | `smoke.js`, `ratelimit.js` — `host:address`, for an environment whose name does not resolve where the script runs; the address may carry a port                        |
| `INSECURE`              | unset                   | `smoke.js`, `ratelimit.js` — accept a certificate from a CA that is not publicly trusted                                                                               |
| `SITE`                  | unset                   | `smoke.js` — also fetch `/` and expect HTML: `BFF_HOST` is an Ingress, and the SPA's route is part of what is checked                                                  |
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

The defaults are calibrated for a **local run against a laptop**, with the dev database's seeded data. k6 sends `Accept-Encoding: gzip`, and the BFF
compresses since #1206, so a run before that change and a run after it are not comparable. They are regression detectors, not SLOs: loose enough
that an ordinary machine under ordinary background load does not trip them, tight enough that an accidental N+1 or a dropped index does.

Production exists ([ADR-012](../docs/adr/ADR-012_CLOUD_PLATFORM.md), accepted) and the thresholds have not moved since they were set against a laptop.
**Re-baselining against real infrastructure is a deliberate act**, and #297 is the issue that does it from real traffic. Raising a threshold because a run
went red is how a performance suite becomes decorative.

## Lighthouse baseline

Lighthouse measures the page a visitor gets, which k6 cannot: rendering, layout shift, image delivery, compression as the browser sees it. It runs on demand
against the live origin, never a dev server, because caching and compression are deployment properties (#292). Three runs per cell, the median reported,
because one run is not a measurement. The command, from any directory:

```bash
CHROME_PATH="/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" \
npx --yes lighthouse@12 https://prod-check.event-junkie.de/de/events --preset=desktop \
  --output=json --output-path=./list-desktop-1.json --quiet --chrome-flags="--headless=new"
```

Drop `--preset=desktop` for the mobile profile. The PageSpeed Insights API is the same engine on Google's network; its keyless quota was exhausted on the day
below, so these are local runs on a Mac over a home connection, with Lighthouse's own throttling.

**2026-09-07, Lighthouse 12.8.2, `prod-check`, v0.6.0.** Scores are medians of three, the range in brackets where it moved.

| Page                | Profile | Performance | Accessibility | Best practices | SEO | LCP   | CLS  | TBT   |
| ------------------- | ------- | ----------- | ------------- | -------------- | --- | ----- | ---- | ----- |
| `/de/events`        | mobile  | 77 (73–77)  | 100           | 100            | 69  | 1.4 s | 0.65 | 36 ms |
| `/de/events`        | desktop | 83 (81–84)  | 100           | 100            | 69  | 0.3 s | 0.34 | 0 ms  |
| `/de/events/<slug>` | mobile  | 76 (75–76)  | 100           | 100            | 69  | 1.9 s | 0.79 | 42 ms |
| `/de/events/<slug>` | desktop | 87 (87–88)  | 100           | 100            | 69  | 0.5 s | 0.27 | 0 ms  |

What the numbers say, and what was done with each:

- **SEO 69 is `prod-check`, not the site.** The one failing SEO audit is `is-crawlable`, because that host sends `X-Robots-Tag: noindex, nofollow` on purpose
  (#286). It reads 100 on the apex, or the flip has gone wrong.
- **CLS is the finding.** Every cell is over Google's 0.25 line for poor. The footer moves by the height of the content once the fetch completes, because each
  view's `<main>` shows a one-line loading text until then. The detail page adds the poster: lazy-loaded although it is the LCP, and flagged unsized. Filed
  as #1207 rather than fixed here.
- **JSON was not compressed.** `uses-text-compression` listed only `/api/**` URLs: 41 KiB on the list page that gzip makes 9 KiB. The BFF gzips since #1206.
- **Not actionable, and recorded so the next run does not rediscover them:** `bf-cache` reports "Internal error" on every run, a Lighthouse limitation on
  headless Chrome; `dom-size` on the list page is 925 elements for 20 cards and their filters, which is the page.

**Since #1698 the row above is the last one taken by hand.** `.github/workflows/lighthouse.yml` runs the same four cells after every successful production
deployment and weekly, through `scripts/lighthouse.sh`, and ADR-033 says why that one runs from Actions while the smoke runs in the cluster: production is
public and staging is not. Its numbers are in the run's step summary and its JSON reports are artifacts for 30 days. **There is no trend store**: OpenObserve
is `ClusterIP` on both clusters with no Ingress, so nothing in Actions can write to it, and #298 step 2 still owns the question. Read the last four weeks of
runs, not this table.

Four things the workflow does that a hand run does not have to, and each is a reason the numbers moved:

- **It pins Lighthouse** in `perf/lighthouse/package.json`. The table above is 12.8.2 and the workflow runs 13.x, which scores the same page differently — a
  step between the row above and the first workflow run is the tool, not the site.
- **It gates only what is deterministic**: accessibility and best practices at 100, and SEO. Performance, LCP, CLS and TBT are reported and not gated, for
  reason 1 below.
- **`is-crawlable` is asserted, not waived.** On a host that is not the apex the script reads the `X-Robots-Tag` header itself and demands that `is-crawlable`
  is the _only_ failing SEO audit, then accepts 69. On the apex it demands 100 and grants no exception, so #939's flip needs no edit.
- **The detail slug comes from `/api/events` at run time**, the first event with a poster. A literal slug becomes a 404 the day the event passes. The cost is
  that the detail row compares different events between runs; the list row is the comparable one.

**CLS is not gated yet.** Every cell above is over Google's 0.1 line, so the gate would be red on its first run and then switched off. `CLS_BUDGET=0.1` is the
value to set the day #1207 closes. The number to watch is still CLS, then LCP on mobile.

## The in-cluster smoke

`smoke.js` is also the chart's second `helm test` hook (#1697): `deploy/charts/event-junkie/templates/tests/smoke-test.yaml` runs it from a `grafana/k6` pod
after every install and upgrade on staging and production, and Flux rolls the release back when it fails. The chart carries no copy of the script.
`deploy/charts/event-junkie/files/perf/` holds symlinks into this directory, so a change here is a change to the hook, and the hook is the reason to keep this
script cheap and deterministic.

Three things differ from a run on a laptop, and each is a value in the chart:

- **It goes through the Ingress, not to the BFF's Service.** `BFF_HOST` is the visitor's origin, and `RESOLVE` sends that name to Traefik's Service inside
  `kube-system`, because staging's name does not resolve in-cluster and production's resolves to the node. `SITE=true` adds `GET /`.
- **Status and shape are the gate, latency is not.** `tests.smoke.latencyBudgetMs` (10 s) replaces all three `THRESHOLD_*_MS` values. A cold JVM after a
  rollout must not roll a release back, and a slow endpoint is a trend to read, not a deploy to refuse.
- **Staging does not verify the certificate.** Let's Encrypt's staging CA is untrusted on purpose, so `tests.smoke.verifyTls` is false there. Production
  verifies, because an unissued certificate is exactly what a post-deploy check should catch.

The pod prints the summary export on one line after the run, without `setup_data`, and k6's text summary only when the run failed. The collector ships
every container's stdout, so each reconcile leaves one JSON row in OpenObserve under `k8s_container_name = 'smoke-test'`. That is the trend store for this suite until #298 step 2 lands. ADR-033 says why the hook runs in
the cluster and not from Actions.

## Why there is no CI workflow (yet)

Considered, and deliberately not added, for the two suites that measure. Three reasons, each of which is also the condition under which the answer changes:

1. **There is nothing representative to run `load.js` and `spike.js` against from CI.** Numbers from a shared GitHub runner — noisy neighbours, no dedicated
   CPU, variance of several hundred percent between runs — are not a baseline, and a threshold set loosely enough to survive them catches nothing. Staging
   exists, and CI cannot reach it (ADR-033), so the two run on demand over the tunnel. A load generator on the node it measures would not be a measurement
   either. → _Point them at staging from somewhere that is not the node, once #298 step 2 has somewhere to keep the numbers._
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
