# Performance tests (k6)

Load and performance tests for the **BFF's public read API**, written for [k6](https://k6.io).

```bash
brew install k6                       # macOS
scripts/dev-env.sh up bff             # the tests need something to talk to

k6 run perf/smoke.js                  # every endpoint once — is it all still working?
k6 run perf/load.js                   # sustained realistic load — does latency stay flat?
k6 run perf/spike.js                  # a sudden surge — does it recover?
k6 run perf/search.js                 # header type-ahead — does search keep up with people typing?
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
| `search.js`    | ramp to N typists, hold   | Does the header search keep up when people type, at the SPA's 250 ms debounce?       |

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

| Variable                  | Default                 | Notes                                                                                                                                                                  |
| ------------------------- | ----------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `BFF_HOST`                | `http://localhost:8080` | **An origin, not a path.** The scripts append `/api` themselves, because the BFF serves that prefix everywhere — see below. Adding it here asks for `/api/api/events`. |
| `VUS`                     | `20`                    | `load.js` — peak virtual users                                                                                                                                         |
| `DURATION`                | `2m`                    | `load.js` — how long to hold the peak                                                                                                                                  |
| `PEAK`                    | `100`                   | `spike.js` — peak virtual users                                                                                                                                        |
| `STRICT`                  | unset                   | `spike.js` — apply the standard thresholds                                                                                                                             |
| `RESOLVE`                 | unset                   | `smoke.js`, `ratelimit.js` — `host:address`, for an environment whose name does not resolve where the script runs; the address may carry a port                        |
| `INSECURE`                | unset                   | `smoke.js`, `ratelimit.js` — accept a certificate from a CA that is not publicly trusted                                                                               |
| `SITE`                    | unset                   | `smoke.js` — also fetch `/` and expect HTML: `BFF_HOST` is an Ingress, and the SPA's route is part of what is checked                                                  |
| `WAIT_FOR_ORIGIN_SECONDS` | `0`                     | `setup()` — poll `GET /meta` this long before giving up. The chart's hook sets 120, for a certificate still being issued (#1892)                                       |
| `VISITS`                  | `4`                     | `ratelimit.js` — first-time page loads the browsing scenario performs                                                                                                  |
| `ABUSE_STREAMS`           | `10`                    | `ratelimit.js` — concurrent streams in the abuse scenario; must stay under `inFlightRequests`                                                                          |
| `THRESHOLD_DETAIL_MS`     | `300`                   | p95 budget for single-row lookups                                                                                                                                      |
| `THRESHOLD_LIST_MS`       | `600`                   | p95 budget for paged list endpoints                                                                                                                                    |
| `THRESHOLD_CALENDAR_MS`   | `1200`                  | p95 budget for the calendar range query — the heaviest read in the API                                                                                                 |

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

The defaults describe **staging**, measured on 2026-10-03 (#2411): `load.js` from the staging node against the BFF's ClusterIP, about 3900 events.
The BFF autoscales from one replica to two at 70 % of its 200m CPU request; the first run took it to two, and every later run ran on two. The node
has four vCPUs and shares them with the importer and PostgreSQL. A port-forward over the tunnel measures the link, not the BFF. Node-to-pod traffic
also skips Traefik, its rate limit and the NetworkPolicy, so the load is harsher than real traffic.

Default shape: 30 s ramp, 2 min hold. Two runs that overlapped a staging rollout were discarded and repeated; the third 20-VU run started 47 s after
one, and the fourth ran on pods several minutes old.

| VUs | detail p95 | list p95 | calendar p95 |
| --: | ---------: | -------: | -----------: |
|  20 |      10 ms |    20 ms |        93 ms |
|  20 |      15 ms |    32 ms |       759 ms |
|  20 |      20 ms |    42 ms |      1428 ms |
|  20 |      18 ms |    37 ms |       714 ms |
|  50 |       7 ms |    10 ms |        49 ms |
|  50 |       5 ms |     7 ms |       718 ms |
|  50 |       7 ms |     9 ms |        52 ms |

One request of about 47,000 failed: a `GET /meta` that hit k6's 60 s timeout, with nothing logged by either BFF pod.

**The rule: the worst run's p95, times 1.5, rounded up to 50 ms.** That gives `detail` 50 and `list` 100. Change the rule on purpose, and record the new
runs here.

**These numbers measure a cached BFF.** The medians are 1 to 4 ms because `ResponseCache` answers most reads for 60 s. A slow query shows only on the
misses, which is why 50 VUs is faster than 20: more requests share each fill.

**`calendar` stays at 1200 ms, and a staging run can fail it today.** When the cached calendar expires, every waiting request runs the calendar query
itself. That is the 714, 718, 759 and 1428 ms runs, with a median near 30 ms throughout. #2529 loads a miss once per key. Re-measure after it lands, and set
`calendar` by the rule then. Raising a threshold because a run went red is how a performance suite becomes decorative.

**A laptop run sets its own budget**, because a laptop and the dev database are not staging:
`k6 run -e THRESHOLD_DETAIL_MS=300 -e THRESHOLD_LIST_MS=600 perf/load.js`. Those were the defaults before this measurement.

`smoke.js` keeps 300, 600 and 1200 ms (`SMOKE_THRESHOLD_MS`): one cold request per endpoint, often over the tunnel, where the round trip alone exceeds
50 ms. The post-deploy smoke hook does not use either set. `tests.smoke.latencyBudgetMs` in the chart sets all three to 10 s, because a cold JVM after a
rollout must clear it.

## Where the session mix comes from

A load test's p95 only describes traffic that can occur, so `load.js` weights its sessions from production traffic: one week, 2026-09-25 to 2026-10-02
(#297). Every session starts with `GET /meta`, as the SPA does.

| Session                                | Weight | Measured as                                                       |
| -------------------------------------- | ------ | ----------------------------------------------------------------- |
| Home, then one event                   | 30 %   | `GET /events/today`, 241 calls                                    |
| Events list, filtered, then one event  | 35 %   | `GET /genres` minus the calendar, about 275                       |
| Land on an event from outside the site | 15 %   | human page loads of `/<lang>/events/<slug>` in the nginx log, 113 |
| Calendar                               | 10 %   | `GET /events/calendar`, 94 calls                                  |
| Venues list, one venue                 | 10 %   | `GET /venues` minus the filter bar's calls, about 100             |

**Count a page by the request only that page makes on mount.** The detail endpoints are useless for this: the injector reads an event for every detail page
nginx serves, and Baiduspider runs the SPA, so `/api/events/{slug}` and `/api/artists/{slug}` showed tens of thousands of calls against about a hundred human
landings. Human page loads come from the nginx log with the `NOT_A_PERSON` pattern in `scripts/o2-query.sh`. It also removes three user agents with no bot
word: `Nexus 5X Build/MMB29P` (Google's renderer), `moto g power` (Lighthouse mobile) and `Chrome/48.0.2564.116` (an old crawler). Artist and promoter pages had
under ten human landings in the week, so `load.js` leaves them out.

**The numbers are small and still carry noise.** A few hundred sessions a week, and Lighthouse desktop runs share a user agent with real Mac Chrome. To
re-derive the mix, count the mount requests over the 14 days OpenObserve keeps, count event landings with the `PAGE_LOAD` and `NOT_A_PERSON` patterns
of `scripts/o2-query.sh`, and replace this table and the comment in `load.js` together:

```bash
scripts/o2-query.sh production sql "SELECT path, COUNT(*) AS n FROM default WHERE k8s_app_component = 'bff' AND path IN ('/api/events/today','/api/events/calendar','/api/genres','/api/venues') GROUP BY path" --hours 336
```

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
- **CLS was the finding, and #1207 fixed it.** Every cell was over Google's 0.25 line for poor. The footer painted at the bottom of the viewport while a view
  loaded, then dropped below the fold. `#main-content` in `App.vue` is now a screen tall, so the footer never paints above the fold. The "unsized" poster was
  a false culprit: `unsized-images` passes, and the `<img>` carries `width` and `height`. The table below is the same four cells on 2026-09-24, Lighthouse
  13.5.0, a local `vite preview` of `main` against the same build with the fix, both proxying `/api` to `prod-check`. Medians of three:

    | Page                | Profile | CLS before | CLS after |
    | ------------------- | ------- | ---------- | --------- |
    | `/de/events`        | mobile  | 0.68       | 0.035     |
    | `/de/events`        | desktop | 0.34       | 0.001     |
    | `/de/events/<slug>` | mobile  | 0.79       | 0         |
    | `/de/events/<slug>` | desktop | 0.34       | 0         |

    The first production run after the release read 0.07 on list mobile: the filter bar wrapped once its venue list arrived, which #1830 fixed. The LCP poster is eager with `fetchpriority=high` on both pages. The LCP
    times did not move: the request still cannot start before the JavaScript has fetched the event.

- **JSON was not compressed.** `uses-text-compression` listed only `/api/**` URLs: 41 KiB on the list page that gzip makes 9 KiB. The BFF gzips since #1206.
- **Not actionable, and recorded so the next run does not rediscover them:** `bf-cache` reports "Internal error" on every run, a Lighthouse limitation on
  headless Chrome; `dom-size` on the list page is 925 elements for 20 cards and their filters, which is the page.

**Since #1698 the row above is the last one taken by hand.** `.github/workflows/lighthouse.yml` runs the same four cells after every successful production
deployment and weekly, through `scripts/lighthouse.sh`, and ADR-033 says why that one runs from Actions while the smoke runs in the cluster: production is
public and staging is not. Its numbers are in the run's step summary and its JSON reports are artifacts for 30 days. **There is no trend store**: OpenObserve
is `ClusterIP` on both clusters with no Ingress, so nothing in Actions can write to it, and #298 closed without a second store. Read the last four weeks of
runs, not this table.

Four things the workflow does that a hand run does not have to, and each is a reason the numbers moved:

- **It pins Lighthouse** in `perf/lighthouse/package.json`. The table above is 12.8.2 and the workflow runs 13.x, which scores the same page differently — a
  step between the row above and the first workflow run is the tool, not the site.
- **It gates only what is deterministic**: accessibility and best practices at 100, SEO, and CLS at 0.1 (`CLS_BUDGET`). CLS measures where boxes land,
  not how fast, so the runner does not move it. Performance, LCP and TBT are reported and not gated, for reason 1 below.
- **`is-crawlable` is asserted, not waived.** On a host that is not the apex the script reads the `X-Robots-Tag` header itself and demands that `is-crawlable`
  is the _only_ failing SEO audit, then accepts 69. On the apex it demands 100 and grants no exception, so #939's flip needs no edit.
- **The detail slug comes from `/api/events` at run time**, the first event with a poster. A literal slug becomes a 404 the day the event passes. The cost is
  that the detail row compares different events between runs; the list row is the comparable one.

The number to watch is LCP on mobile.

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

The pod prints k6's text summary only when the run failed. After every run, `lib/summary-line.js` prints the summary export on one line without `setup_data`, the discovered slugs. It parses the export, because k6 writes its keys in a random order (#2392). The collector ships
every container's stdout, so each reconcile leaves one JSON row in OpenObserve under `k8s_container_name = 'smoke-test'`. The row lands in the stream `smoke_test`, kept 60 days (#2393). ADR-033 says why the hook runs in
the cluster and not from Actions.

## Why there is no CI workflow (yet)

Considered, and deliberately not added, for the two suites that measure. Three reasons, each of which is also the condition under which the answer changes:

1. **There is nothing representative to run `load.js` and `spike.js` against from CI.** Numbers from a shared GitHub runner — noisy neighbours, no dedicated
   CPU, variance of several hundred percent between runs — are not a baseline, and a threshold set loosely enough to survive them catches nothing. Staging
   exists, and CI cannot reach it (ADR-033), so the two run on demand over the tunnel. A load generator on the node it measures would not be a measurement
   either. → _Point them at staging from somewhere that is not the node._
2. **The functional coverage is already there and is better.** A CI run would need Postgres, then the importer to apply the Flyway migrations (the BFF owns
   none), then the BFF — and would end up asserting that every endpoint returns 200 against an **empty** database. The Testcontainers integration tests already
   do that with real data, in-process, on every build. → _A perf workflow should measure, not duplicate._
3. **Trend matters more than a pass/fail gate.** A single red build tells you almost nothing about performance; a p95 that has drifted 40% over two months tells
   you a lot. That wants results stored over time (k6 Cloud, or Prometheus remote-write into the monitoring stack ADR-012 already calls for), not a threshold in
   a workflow. → _The smoke rows go to OpenObserve's `smoke_test` stream, kept 60 days (#2393)._

Until then these run on demand, against a real database.

## Adding a scenario

Endpoints live in [`lib/api.js`](lib/api.js) — one place to follow when a controller changes. Thresholds and shared options live in [
`lib/config.js`](lib/config.js). A new script should reuse both rather than issuing bare `http.get` calls, so it inherits the tagging that makes the per-group
thresholds work.
