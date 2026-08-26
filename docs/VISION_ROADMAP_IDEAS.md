# Vision, Roadmap & Ideas

## The short version

Four phases. **Phase 1 is launch** — hosting, legal, the shared-link head tags — and it is split across three release
milestones. **Phase 2 is depth**: more venues, the map, related events. **Phase 3 adds accounts**, and the open
question there is whether stage 1 needs one at all. **Phase 4 is the social and ecosystem layer**, and it only starts
working once RSVP volume exists.

This is the longer-form product context: **what** Event Junkie is, and **where** it is headed. The granular,
actionable backlog lives in the **[issue tracker](https://github.com/enorm-labs/event-junkie/issues)**. Brand, voice
and design live in **[BRANDING.md](BRANDING.md)**. This document stays at the level of _direction_, and links down to
**milestones** rather than duplicating tasks.

---

## Vision

### What it is

Event Junkie is a discovery app for **music events in Berlin**: concerts, club nights, festivals. It aggregates them
from venue and promoter websites into one fast, filterable place, and always links back to the original source.

- App name: **Event Junkie** (→ event-junkie.de), and the same name internally in `event-junkie` identifier form.
- Tagline: _"Can't get enough of Berlin."_ (voice & branding: see [BRANDING.md](BRANDING.md))

### Who it's for & positioning

Think Resident Advisor, Bandsintown, Eventbrite or Songkick — **but for all of Berlin's music scene, and better**:

- **All genres, not only Techno/Electronic** — the incumbents skew electronic or ticketed-only.
- **Small and underground events too**, not just the big ticketed shows.
- Closest existing references: [clubguideberlin.de](https://www.clubguideberlin.de/),
  [gaesteliste030.de](https://www.gaesteliste030.de/).

### The questions it should answer

A product test rather than a feature list — a plausible visitor question that cannot be answered by changing a filter is a gap:

- What's on tonight / tomorrow / this weekend — and what's on **near me**?
- What's happening in my favourite clubs this week?
- When does artist X next play in Berlin, and at which venue?
- When do the artists, venues and promoters **I follow** play here?
- Where are my friends going, and can I bring them along?

The first two are answerable today, with the date-range presets and the venue, genre, district and free-text filters.
The "near me" question waits on the venues map and a radius search (Phase 2). The last two are the whole point of
Phases 3 and 4.

### Scope

- **In scope now:** music events across Berlin venues — clubs, concert halls, bars and the rest.
- **Maybe later:** other cities, and possibly non-music events. Both are deliberately **out of scope** for the MVP.

### Guiding principles

- **Aggregate and link back, do not republish.** Store only the structured fields needed, and link to the source for
  the full details. Respect venue copyright — see [ADR-007](adr/ADR-007_WEB_SCRAPING_STRATEGY.md).
- **Data quality over volume** — better to show fewer, correct events than a noisy firehose.
- **Fast, clean, mobile-first discovery.**
- **Be a good scraping citizen** — polite rate limits, transparent User-Agent, off-peak scheduling.

---

## Roadmap

High-level phases and the shape of the journey — **not** a checklist. The concrete tasks behind each phase are the issues in the linked **milestone**.

**Phase 1 is split across three release milestones.** It holds work ranging from "write the Helm chart" to "check a
link preview in WhatsApp". One progress bar over that mix says nothing about whether launch is reachable.

### Phase 0 — Foundation ✅ _(built)_

The core product exists end-to-end:

- **Backend** — Kotlin + Spring Boot on a reactive stack (WebFlux, R2DBC, Flyway), split into a Spring-Modulith **importer** and a public **BFF** (see
  the [ADRs](adr/)).
- **Importers** — six Berlin venues live: Cassiopeia, Privatclub, Madame Claude, Astra, Lido, SO36, with a `/scaffold-importer` skill for adding more.
- **Frontend** — Vue 3 app with a calendar view, event/artist/venue/promoter detail pages, and district / free / sold-out filters plus genre tags.

### Phase 1 — MVP / Go-live 🔴 _(in progress)_

Turn the working prototype into a live public product. → **[v0.2 — Deployable](https://github.com/enorm-labs/event-junkie/milestone/2)** · **[v0.3 — Launch-ready](https://github.com/enorm-labs/event-junkie/milestone/3)** · **[v1.0 — Go-live](https://github.com/enorm-labs/event-junkie/milestone/4)**

- Cloud platform, domain (**event-junkie.de**), CI/CD, and a first deploy.
- Hardening the path to production: auth & authorization, BFF caching, API protection, a reusable test/seed dataset, and the go-live checklist (security,
  monitoring, backups, recovery).
- Legal readiness: Impressum, GDPR, accessibility, FOSS attributions, scraping legality.

### Phase 2 — Coverage & polish 📈 _(post-launch)_

Make it comprehensive, discoverable and pleasant. → **[Phase 2 — Coverage & polish](https://github.com/enorm-labs/event-junkie/milestone/5)**

- Scale importer coverage toward the full venue list in [EVENT_DATA_SOURCES.md](EVENT_DATA_SOURCES.md), and enrich
  venue metadata.
- An **admin imports-status dashboard** to watch import health and failures.
- A venues page with a map, a full UX and mobile pass, and the remaining SEO surfaces. Done already: i18n and l10n,
  the sitemap, `hreflang`, canonical URLs and `schema.org` structured data. RSS and the map remain.
- **Server-side head tags for shared links.** Every page serves an empty `<div id="app">`. A scraper that does not run
  JavaScript shows the generic site title and description for _every_ shared link, event pages included. That covers
  Slack, WhatsApp, iMessage, Facebook and LinkedIn. Sharing a specific event is a primary way a nightlife product
  spreads. **[ADR-014](adr/ADR-014_RENDERING_STRATEGY.md)** decides it: **meta injection**, not prerendering. No
  build-time rendering of any route, because it buys nothing for the pages people share, and daily imports make a
  detail route stale by construction. The work splits in two. The shared module computing each page's head tags can be
  built now, and it also closes the missing per-page `og:description`. The transport that rewrites the response waits
  for ADR-012 to be executed. Full SSR is deferred behind a named trigger, rather than anticipated: Search Console
  showing detail pages indexed poorly.
- **Related events** on detail pages (same venue, genre or artist), and a **"near me" radius search** driven by the
  browser's location. Both work without accounts, and both depend on venue coordinates being trustworthy first.
- "Missing event / venue" and feedback forms.

### Phase 3 — Accounts & personalization 👤 _(Expansion stage 1)_

Give people a reason to come back. → **[Phase 3 — Accounts & personalization](https://github.com/enorm-labs/event-junkie/milestone/6)**

- Login and profile, with **follow/favourite** for artists, venues, districts, promoters and genres. It both filters
  events and drives **notifications**, in two YouTube-style steps: follow, then get notified.
- Favourites (Merkliste), reminders, RSVP ("interested" / "going"), a customizable start page, recommendations.
- **Saved searches** — keep a filter combination and be told when a new event matches it. That is the same
  subscription mechanism as a follow, pointed at a query.
- **Notifications that are scoped, not firehoses.** "Notify me when this artist plays _in Berlin_", not "whenever this
  artist announces anything". That rule keeps a follow from becoming noise, and it matters most the day a second city
  exists.
- **Does stage 1 need an account at all?** Take that decision before building it. Follows and favourites could live
  on the device, in localStorage or IndexedDB. A login would then be needed only for syncing across devices, and for a
  notification that must be delivered server-side. Account-first is the harder default to walk back.
- User- and venue-submitted events with review-before-publish. It needs RBAC (Keycloak), plus automatic plausibility
  checks — a near-duplicate search, a source-URL check — ahead of the human approve or decline.

### Phase 4 — Social & ecosystem 🤝 _(Expansion stage 2)_

Turn discovery into a network and open the data up. → **[Phase 4 — Social & ecosystem](https://github.com/enorm-labs/event-junkie/milestone/7)**

- A social layer: connect with friends and see which events they are interested in or going to. Plus following other
  users, an activity timeline, and **inviting friends to a specific event**. Going together is the actual use case,
  and seeing where they go is the weaker half of it.
- Ranking by popularity (RSVPs) and by artist popularity. Richer venue and artist profiles. **Collaborative
  recommendations** — "people going to this also go to that" — which only start working once RSVP volume exists.
- **Ratings and reviews** for events and promoters. Worth a _whether_, not only a _when_: it adds moderation duty, and
  a thin review count reads worse than none.
- Integrations with Spotify, Deezer, SoundCloud and Resident Advisor, to notify when a favourite artist plays. That
  includes **importing the artists someone already follows there** in one step, which is the fastest way to make a new
  account useful. Facebook Events and Pages were in the original idea list, from the era when the Graph API was open.
  Check what Facebook still permits before planning anything on it.
- A club map showing events nearby. An iCal export, and **calendar subscriptions that stay in sync**. That is an ICS
  feed per follow or saved search, so a new matching event lands in Google Calendar without a manual export. And a
  public API with API management.

### Phase 5 — Beyond Berlin 🌍 _(bigger bets)_

Expand to other cities and broaden scope once the Berlin experience is strong. Deliberately has **no milestone** — it is the direction of travel, not scheduled
work. Tracked as [issue #403](https://github.com/enorm-labs/event-junkie/issues/403).

---

## Idea sources & references

- Extended idea backlog:
  [Google Doc](https://docs.google.com/document/d/1UkxdJECxvB6noW-n8dzX-r0du18M9-ggp6iWeapHpvI/edit).
- Competitors and inspiration: Resident Advisor, Bandsintown, Eventbrite and Songkick, plus clubguideberlin.de and
  gaesteliste030.de.
- Alternative names considered (not chosen): Berlin Club Guide (berlinclubguide.de / berlin-clubs.de).
