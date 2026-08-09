---
slug: public-api-conversational-epic
title: Public API and conversational access
type: Feature
milestone: Phase 4 — Social & ecosystem
labels: ["size:XL", "area:bff"]
priority: P2
status: Backlog
---

**The outcome.** The data is usable by things that are not this website.

**What it absorbs**

- a **public API for third-party apps**, with API management — subscriptions, keys, quotas
- a **chatbot and/or MCP server** to find events and answer questions about events, artists, venues,
  districts, promoters and genres
- a **club map** showing events nearby *(note: the venues map in Phase 2 delivers most of this; what
  remains here is the standalone, shareable version)*

**The question that gates all of it:** the data is aggregated from venue websites under an
*aggregate and link back* principle (ADR-007). Republishing it through a public API is a materially
different act from displaying it on one site, and the scraping-legality review has to cover that
case explicitly rather than by extension.

An MCP server is the cheapest entry point and needs no API management, which makes it a reasonable
first step and a useful test of whether anyone wants this at all.
