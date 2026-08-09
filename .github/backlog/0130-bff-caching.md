---
slug: bff-caching
title: Add caching to the BFF
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:bff", "size:M"]
priority: P1
status: Backlog
---

The data changes at most once per import cycle, and the read patterns are extremely repetitive —
"what is on tonight" is the same query for everyone who opens the site that evening.

Worth deciding together with the rate-limiting issue, since edge caching at Cloudflare and
application caching in the BFF solve overlapping parts of the same problem, and doing both blindly
means two invalidation stories instead of one.

**Done when**

- [ ] Cache scope and invalidation decided — in particular what happens the moment an import
      finishes
- [ ] `perf/load.js` shows the improvement it was supposed to buy
