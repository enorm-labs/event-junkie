---
slug: calendar-sync-integrations-epic
title: Calendar subscriptions and external music-service integrations
type: Feature
milestone: Phase 4 — Social & ecosystem
labels: ["size:XL", "area:bff", "blocked"]
priority: P2
status: Blocked
blocked-by: [accounts-follows-notifications-epic]
---

**The outcome.** Events someone cares about arrive where they already look — their calendar, and
their music services' idea of which artists they follow.

**Definition of done.** A new matching event appears in someone's Google or Apple Calendar without
anyone exporting anything.

**What it absorbs**

- **iCal support** — export or import to Google Calendar or a file
- **and beyond one-off export, a calendar that stays in sync:** an ICS feed URL per follow or per
  saved search. This is the version that matters; a one-time export is stale the moment it is made
- **Spotify / Deezer / SoundCloud / Resident Advisor** — notify when favourite artists play,
  including a one-step **import of the artists someone already follows there**. That import is the
  fastest way to make a fresh account useful instead of empty, which is otherwise Phase 3's hardest
  problem
- **Facebook (Events and Pages)** — pull "interested"/"going" and follow artists already liked
  there. From the original idea list, written when the Graph API was open: **check what it still
  permits before planning anything on it**

The ICS feed is the cheapest item here and the one with the best ratio — it needs no partner API,
no OAuth and no ongoing relationship with a third party.
