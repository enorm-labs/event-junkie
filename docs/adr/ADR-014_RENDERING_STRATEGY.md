# ADR-014: Rendering strategy — how HTML reaches crawlers and scrapers

## Status

**Proposed.** The decision stands. **None of it is built yet.**

> Closes the question [ADR-013](ADR-013_LOCALISATION.md) §Consequences deferred: _"SSR / prerendering — wanted for SEO
> and tracked separately"_. The SEO work then ran into it. `hreflang` had to be carried by the sitemap, because a
> script-injected annotation is unreliable for crawlers (§Context).
>
> The rendering-delay figures below were measured once and are indicative rather than current.

## Context

Every URL on this site serves the same body: `<div id="app"></div>`. Everything else — headings, listings, `hreflang`, `canonical`, JSON-LD — exists only after
JavaScript runs.

That sounds worse than it is. This ADR is worth writing mostly to separate the genuinely broken part from the
folklore.

### Problem 1 — link previews are broken. This one is real

Slack, WhatsApp, iMessage, Discord, LinkedIn and Facebook scrapers do not execute JavaScript. They read the served
HTML. `usePageTitle` updates `og:title` on the client, so every shared link previews as the generic site title. And
**`og:description` is not updated per page at all**, so the description is site-level on every route. A specific Friday
line-up and the imprint produce identical preview cards.

For a nightlife product this is not cosmetic. Sharing a specific event with friends _is_ a primary distribution
channel, and the moment where the preview does the selling. This problem is permanent. Search-engine improvements do
not touch it, and neither does any of the sitemap or structured-data work already shipped.

### Problem 2 — indexing latency. Smaller than it first appeared

Googlebot's render pass is widely said to take "days to weeks". **Current data does not support that**, and a decision
resting on it would rest on 2018-era folklore:

- The median crawl-to-render gap is around [10 seconds, with the 25th percentile inside four](https://www.onely.com/blog/googles-rendering-delay-5-seconds/).
- Across 100,000+ Googlebot fetches, one study
  found [Google rendered 100% of indexable HTML pages, with no measurable penalty for JavaScript complexity](https://nadiamohamed.me/insights/javascript-seo/).

The honest caveat, which does apply to us: that
delay [scales with crawl priority, and low-priority sites can still wait a week or more](https://seolinkscan.com/blog/javascript-seo-guide-2026). A brand-new
site with no inbound links is exactly a low-priority site. So this is a real risk **at launch specifically**, and it
decays as the site establishes itself. It is a
reason to watch Search Console, not a reason to build a rendering architecture up front.

### What is already mitigated

| Surface                                    | State                                                                                                 |
| ------------------------------------------ | ----------------------------------------------------------------------------------------------------- |
| `hreflang` alternates                      | Carried by `sitemap.xml`, a static file needing no rendering — the primary annotation today           |
| `canonical`, `og:url`, `og:locale`         | JS-injected; reach Googlebot, reach no non-JS consumer                                                |
| `schema.org` JSON-LD                       | JS-injected; reaches Googlebot, which is enough — there is no non-JS consumer of structured data      |
| **Per-page `og:title` / `og:description`** | **The gap.** Title is JS-injected; description is not set per page at all. Neither reaches a scraper. |

So what rendering must buy is narrow and specific: **correct per-page head tags in the served HTML for data-driven routes.** Not body content, not first paint,
not the static pages.

### The requirement that removes half the option space

Data-driven pages must always be up to date — events are imported daily, change daily, and expire.

Worth noting what that implies: **the SPA already satisfies this**, because it fetches at view time. Freshness is
therefore not an argument _for_ server rendering. It is an argument _against_ anything computed at build time.

### The deployment it has to fit

[ADR-012](ADR-012_CLOUD_PLATFORM.md) puts the frontend in an nginx container serving a static `dist/`, behind Traefik
on Hetzner k3s. All processing is in Germany, and since its 2026-08-10 amendment nothing sits in front of the cluster
at all. It explicitly assumed the frontend has "no server runtime of its own".

### Criteria

1. **Fixes per-page link previews** — the one real problem.
2. **Never serves stale data** — see above.
3. **Degrades safely.** One person maintains this in evenings. An option that fails to a blank page is worse than one
   that fails to a worse preview.
4. **Adds no processor and no jurisdiction question**, per [AGENTS.md §Privacy](../../AGENTS.md) and ADR-012's German-processing choice.
5. **Proportionate.** The problem is a handful of `<meta>` tags. The solution should look like that.

## Candidate options

### Option A — Status quo

Costs nothing, fixes nothing. It is named because it is what happens if this ADR stalls, and because it is genuinely
not catastrophic. Googlebot renders, the sitemap carries `hreflang`, and structured data works. The unmitigated loss is
link previews.

### Option B — Build-time static generation of the static routes (`vite-ssg`)

Prerender the eight static paths × two locales. `vite-ssg` v28.3.0 is compatible with our stack (peers `vite ^8.0.0-0`, `vue-router ^5.0.0-0`, Node ≥20).

**Rejected.** Measured against Criterion 5 it is the worst trade available. It requires restructuring `main.ts` around
`ViteSSG`, migrating head management to `@unhead/vue`, and adding SSR guards to five modules that touch `document`. In
return it prerenders home, the two index pages, about and the three legal pages. **Nobody shares the imprint in a group
chat.** It is the most expensive option that does nothing for Problem 1.

### Option C — Build-time static generation of everything, including detail routes

`includedRoutes` fetches every slug from the BFF at build time.

**Rejected, permanently.** Not for build time or the CI coupling to a database, which are both solvable, but because
it violates Criterion 2 by construction. Importers run daily. An event imported on Tuesday has no HTML until the next
deploy, and a changed line-up keeps serving the old one. Making it correct means a deploy on every import, which is a
worse system than the one we have.

### Option D — Full server-side rendering with hydration

A Node process renders each request. The client then hydrates and behaves as an SPA. This is the "hybrid SPA + SSR"
shape, and the technically complete answer: previews, freshness, no-JS content and first paint, all at once.

Costs, in the order they would actually hurt:

- **Two module-level singletons would leak across requests.** The `vue-i18n` instance's locale is mutated globally by `setI18nLocale`, and `pageTitle` is a
  module-scoped `ref`. Under concurrent SSR, one visitor's German request sets the locale another visitor's English response renders with. This is a
  prerequisite refactor, not a detail.
- **Its failure mode is a blank page** (Criterion 3), against a problem whose current failure mode is a mediocre preview.
- **A Node runtime in production** — a new deployable, patching surface and memory profile, changing ADR-012's assumption.
- Hydration mismatches and server/client double-fetching, both of which are ongoing costs rather than one-off ones.

Not rejected on merit — deferred, with a trigger (§Decision 4).

### Option E — Third-party dynamic rendering (Prerender.io and similar)

**Rejected on two independent grounds.** It routes visitor requests through a new processor, which changes the privacy
notice and needs an Art. 28 contract. That is the category [AGENTS.md](../../AGENTS.md) says to escalate rather than
implement. Google also deprecated dynamic rendering as a long-term solution. A permanent dependency on a workaround its
own author walked away from is a poor trade.

### Option F — Meta injection

Serve the ordinary SPA shell, with the head filled in per route before it leaves our infrastructure. On a request for
`/en/events/{slug}`, take the built `index.html`, recognise the route and fetch the entity from the BFF. Rewrite
`<title>`, `og:*`, `twitter:*` and `canonical`, then stream the result. The body stays `<div id="app"></div>`, so the
SPA boots and renders exactly as it does today.

- **Fixes Problem 1 completely** — scrapers read the head and stop, so a head-only fix is a total fix for them.
- **Always fresh**: the data is fetched per request.
- **Fails safely**: if the injector breaks, visitors get today's behaviour and a generic preview. Nothing goes blank.
- **Touches none of Option D's prerequisites** — no Vue renders on the server, so the singletons are irrelevant and no SSR guards are needed.
- Does **not** fix Problem 2. The body is still empty, so Google still uses its render pass. Given the data above,
  that is an acceptable trade rather than a reluctant one.
- This is not cloaking — every consumer receives the same document.

## Decision

### 1. No build-time rendering, of any route

Options B and C are both rejected. B fails cost/benefit, and C fails the freshness requirement by construction. The
frontend build stays a plain `vite build` producing a static `dist/`. It also **stays independent of the BFF and the
database**, which is a property worth protecting deliberately.

### 2. Meta injection (Option F) for data-driven routes

The primary decision. Event, venue, artist and promoter detail pages get their head tags filled in on the server.
Everything else is served as it is today.

### 3. Build it in two parts, split by what each part depends on

The work divides cleanly, and only one half needs a deployment:

**Now — computing the tags.** One shared module derives `{ title, description, image, canonical }` from a route and
its entity. It is placement-independent and unit-testable, and it carries the trap. **The injector and the client must
produce identical values.** Otherwise a scraper sees one title and a visitor sees another, and `og:url` and `canonical`
flip when JavaScript boots. One module used by both is the only reliable way to hold that.

It is also worth doing on its own merits, with or without an injector. It closes the missing per-page `og:description`
noted in §Context, which is a real gap today and the second finding of the Google SEO-guide review.

**At launch — the transport.** The component that intercepts the response and rewrites the head. This genuinely depends on ADR-012 existing, and building it
against a guessed deployment would be waste.

> **The transport is a small sidecar in k3s** ([#412](https://github.com/enorm-labs/event-junkie/issues/412)). A
> **Cloudflare Worker using `HTMLRewriter`** was the leading candidate, because Cloudflare was already in the request
> path and it fit the free plan. ADR-012's amendment removed Cloudflare from the architecture, so the Worker is not
> available. The sidecar costs a little more operationally, and keeps all processing in Germany. That operational cost
> is simply the price.

**Do not prototype the transport in Vite dev middleware.** The production shape is the sidecar, and a dev-server
approximation would be thrown away.

### 4. Full SSR (Option D) is deferred, with a named trigger

Revisit when **Search Console shows detail pages indexed late or not at all** after launch — evidence, not anticipation. The prerequisite refactor (per-request
i18n and page-title state) should be treated as part of that work rather than done speculatively.

### 5. No third-party dynamic rendering

Option E rejected.

## Consequences

**What this decision buys by _not_ doing things.** Rejecting Option B removes a whole restructuring programme: no
`ViteSSG` entry point, no `@unhead/vue` migration, no SSR guards across five modules, no `import.meta.env.SSR`
branching. The existing `seoTags.ts` and `structuredData.ts` stay exactly as they are.

**Accepted costs:**

- **The head becomes a two-writer surface.** The injector writes it, then the client overwrites it on boot. They must
  agree. §Decision 3's shared module is the mechanism, and it needs a test asserting that the two produce the same
  values for the same route.
- **Detail routes gain a per-request BFF lookup** in front of the HTML response. It needs caching and a timeout, and
  it must fail open. A slow or failing BFF must yield the unmodified shell, never an error page.
- **The transport is a sidecar in the cluster** (settled 2026-08-10). The privacy consequence is the mild one. HTML
  assembly happens on the same German infrastructure that already serves every request, so there is no new processor
  and no new data category. §7.7 still requires it be raised as a change rather than assumed.

**What stays as it is, deliberately:**

- **Bodies remain client-rendered.** Google's render pass handles them, at a cost the data above says is acceptable.
- **One new runtime in the cluster** — the sidecar, and nothing else.

### When to revisit

- **Search Console shows detail pages indexed late or not at all** — the trigger for Option D.
- **The i18n and page-title singletons become per-request for another reason** — testing, or an admin app sharing the
  code. Option D's main prerequisite then disappears, and its cost drops sharply.
- **Preview quality stops being the binding constraint** and first paint starts being one. That is a performance
  argument for SSR, which this ADR does not make.
- **A framework move is on the table anyway.** Nuxt subsumes this entire decision. It is out of scope here, because
  migrating a working app is far larger than the problem justifies. If it is ever considered for other reasons,
  reopen this rather than working around it.

## References

- [ADR-012](ADR-012_CLOUD_PLATFORM.md) — the deployment shape, and the dependency behind the second half of Decision 3
- [ADR-013](ADR-013_LOCALISATION.md) — deferred this decision, and its locale-prefixed URLs are what make per-page
  head tags worth computing
- [`events-frontend/AGENTS.md`](../../events-frontend/AGENTS.md) §SEO surfaces — including the rule that the sitemap, not the head tags, is the annotation that
  currently works
- [LEGAL.md](../LEGAL.md) §7.7 — the standing check any new processor or edge processing must clear
- [Google's rendering delay](https://www.onely.com/blog/googles-rendering-delay-5-seconds/) · [JavaScript SEO in 2026](https://nadiamohamed.me/insights/javascript-seo/) · [crawl priority and render queue](https://seolinkscan.com/blog/javascript-seo-guide-2026)
- [Google Search Central — dynamic rendering](https://developers.google.com/search/docs/crawling-indexing/javascript/dynamic-rendering)
- [Cloudflare `HTMLRewriter`](https://developers.cloudflare.com/workers/runtime-apis/html-rewriter/) — the reference
  implementation of streaming head rewriting. The sidecar does the same job in the cluster
