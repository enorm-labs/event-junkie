---
slug: accounts-follows-notifications-epic
title: Accounts, follows and notifications
type: Feature
milestone: Phase 3 — Accounts & personalization
labels: ["size:XL", "area:frontend", "area:bff", "blocked"]
priority: P2
status: Blocked
blocked-by: [anonymous-first-decision]
related: [event-series-first-class]
---

**The outcome.** Someone can follow an artist, a venue, a district, a promoter or a genre — and be
told, once, in time, and only about Berlin, when something they follow is playing.

**Why it matters.** It is the answer to two of the five product-test questions in
[VISION_ROADMAP_IDEAS.md](../../docs/VISION_ROADMAP_IDEAS.md), and it is the whole reason someone
comes back rather than visiting once.

**Two steps, YouTube-style:** (1) follow, (2) get notified. They are separable and shipping (1)
alone is useful — a follow that only filters the events list already earns its place.

**Definition of done.** A returning visitor sees a page shaped by what they follow, and receives a
notification they did not find annoying.

**What it absorbs**

- follow/favourite for artists, venues, districts, promoters, genres — used both to filter and to
  notify
- **saved searches** — keep a filter combination ("free techno in Neukölln this weekend") and be
  told when new events match. Mechanically the *same subscription* as a follow, pointed at a query
  instead of an entity, so **build the two together rather than twice**
- **scoped notification rules, not a firehose** — "tell me when this artist plays *in Berlin*", not
  "whenever this artist announces anything anywhere". A follow should carry a location and ideally a
  lead time. Cheap while Berlin is the only city, load-bearing the day it is not, and the thing that
  keeps notifications from being the reason people turn them off
- favourites (Merkliste), reminders, a customizable start page, RSVP ("interested" / "going")
- recommendations

**Depends on** the anonymous-first decision, and on RBAC if accounts are chosen (Keycloak).
