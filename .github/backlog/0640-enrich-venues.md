---
slug: enrich-venues
title: Enrich venues — type, description, image, genres, event types
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:data-quality", "importer", "size:L"]
priority: P1
status: Backlog
related: [venue-capacity-metadata]
---

Venue rows carry the minimum: name, address, slug and a hand-written description. What is missing is
most of what would make a venue page worth visiting.

**Wanted:** venue type (club / bar / concert hall / theatre), a scraped description, an image or
photo, the genres it actually programmes, and the event types it hosts.

The description column, API and UI already exist, with hand-written seed blurbs — **scraping one per
venue from its own website is the part that is missing**, and it is the part that scales past the
venues someone happened to write a blurb for.

Venue type also unblocks two frontend filters that are otherwise unbuildable, and the genres would
let venues be browsed by what they play rather than only by district.
