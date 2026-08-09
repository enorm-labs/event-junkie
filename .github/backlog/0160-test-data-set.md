---
slug: test-data-set
title: Create a reusable test data set
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:ci", "area:data-quality", "size:M"]
priority: P1
status: Backlog
---

One curated dataset serving two purposes: **test fixtures** and **populating a local database**.

Today those are separate efforts — tests build their own data, and a local database is filled by
running real imports through `dev-seed.http`, which needs the network and produces whatever the
venues happen to be publishing that week. Neither is reproducible.

**Done when**

- [ ] A fixed dataset covering the shapes that matter: multi-artist bills, festivals, sold-out
      events, free events, missing prices, events with no genre, a multi-room venue
- [ ] Loadable into a local database in one command
- [ ] Usable from tests without a network

Pairs with the golden-fixture work in Pillar 2, which needs real captured HTML for the same reason.
