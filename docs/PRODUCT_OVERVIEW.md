# Event Junkie — Product Overview

> _"Can't get enough of Berlin."_

**A feature inventory: what Event Junkie does today, in present tense.**

This document deliberately does **not** argue the *why*. The motivation, the problem it answers and how it differs from Resident Advisor, Bandsintown/Songkick
and the ticketing sites live in the [README](../README.md#background) and on the site's own About page — one place each, so they cannot drift into three
slightly different pitches. What belongs here is the checklist of what actually exists.

Elsewhere: which *kinds* of event are in scope — [EVENT_SCOPE.md](EVENT_SCOPE.md) · where the product is headed —
[VISION_ROADMAP_IDEAS.md](VISION_ROADMAP_IDEAS.md) · the backlog — [GitHub Issues](https://github.com/enorm-labs/event-junkie/issues) · voice and visual direction — [BRANDING.md](BRANDING.md).

App name: **Event Junkie** (→ event-junkie.de), and the same name internally in `event-junkie` identifier form.

---

## In one line

A **music-event discovery app for Berlin**: it collects events automatically from venue and promoter websites into one fast, filterable feed, always linking
back to the original source for tickets and details.

Scope rule — **if a Berlin venue puts it on a stage in the evening, it is in scope.** What that includes and what is deliberately excluded (sport, participation
formats, trade fairs, and for now classical) is set out in [EVENT_SCOPE.md](EVENT_SCOPE.md).

## What it lets you do

- **Browse and search** upcoming events, with a **calendar view** and a **today** view.
- **Filter** by what you actually care about: date range, event type, **Berlin district** (all 12 boroughs), **genre**, **price range**, **free-only**, and
  **exclude sold-out** — plus free-text search over titles.
- **Drill into details:** dedicated pages for each **event, venue, artist and promoter**, cross-linked so you can jump from an artist to all their Berlin dates,
  or from a venue to its full programme.
- **See the signal at a glance:** "Free" and "Sold Out" badges, event status (e.g. cancelled/postponed), door/start times, prices, line-ups and genre tags.

---

## Current Status

**🚧 In active development — not yet publicly deployed.** The core product works end-to-end locally; the path to a public launch (hosting, domain, auth, legal)
is tracked in [the issue tracker](https://github.com/enorm-labs/event-junkie/issues).

### Live features

**Discovery frontend** (Vue 3)

- Home, **calendar**, and event **search/list** pages, plus **event / venue / artist / promoter** detail pages and an About page.
- Filtering by date range (with **Tonight / This weekend / Next 7 days** shortcuts), event type, venue, district (12 boroughs), genre, price range, free-only
  and exclude-sold-out, with free-text search; "Free" and "Sold Out" badges. One shared filter bar serves both the **list and the calendar** — bar the date
  range, which only the list shows, since the calendar's visible window already is one — and every filter lives in the URL, so a narrowed view is shareable.

**Public read API — BFF** (`/api/…`, OpenAPI/Swagger documented)

- Event search with the full filter set above **+ artist / promoter** filters, pagination and sorting; plus **today** and **date-range calendar** endpoints —
  the calendar accepting the same filter set — and per-slug detail.
- List + detail endpoints for **venues, artists, promoters and genres**.

**Automated data aggregation — Importer**

- **86 Berlin sources live** — clubs, bars, concert halls, open-air spaces, arenas, theatres and a comedy club. The per-venue inventory and its status counts
  live in [EVENT_DATA_SOURCES.md](EVENT_DATA_SOURCES.md); do not restate the number here, restate the link.
- **Scheduled imports** with **change detection** (ETag / Last-Modified), **per-host politeness throttling**, deduplication, and **stale-event cleanup**.
- **Per-source status tracking + retry**, managed via an admin API — imports trigger asynchronously (fire-and-forget) and their status is polled
  (create/enable/trigger/retry sources).
- **Data enrichment at import:** free-event detection, sold-out and event-status detection, and automatic creation + linking of **artists (with roles/billing
  order), promoters and genre tags**.
- A **`/scaffold-importer`** skill that turns adding a new venue into a guided, tested workflow.

**Data model**

- Events, venues, artists, promoters, genre tags and import sources, with many-to-many links (event↔artist, event↔promoter, event↔genre).

### Engineering foundation

- Reactive **Kotlin + Spring Boot 4** backend (WebFlux, R2DBC, Flyway) split into a **Spring-Modulith importer** and a public **BFF**, backed by **PostgreSQL**;
  **Vue 3** frontend.
- Decisions captured as **ADRs**; unit/integration tests across importer, BFF and frontend.

### Not there yet

User accounts & personalization, notifications, a venues map, broader venue coverage (beyond the current eight), and a public deployment. These are the
roadmap — see
[VISION_ROADMAP_IDEAS.md](VISION_ROADMAP_IDEAS.md) and [the issue tracker](https://github.com/enorm-labs/event-junkie/issues).

---

## Keeping this honest

Everything above is a claim about what exists **now**, so it goes stale in exactly one way: a feature ships and nobody updates the list. Two things that have
already gone stale here and are worth not repeating:

- **Counts.** This document said "eight venues live" long after there were 86. Numbers that change belong in the source that owns them —
  [EVENT_DATA_SOURCES.md](EVENT_DATA_SOURCES.md) for coverage — and should be *linked* from here, not copied.
- **The pitch.** The *why* used to live here as well as in the README. It now lives in the README and the About page only.
