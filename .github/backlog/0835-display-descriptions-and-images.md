---
slug: display-descriptions-and-images
title: Decide whether to display event descriptions and source images
type: Task
milestone: Phase 2 — Coverage & polish
labels: ["area:frontend", "area:legal", "needs-decision", "size:M"]
priority: P2
status: Blocked
blocked-by: [content-copyright-per-source]
---

Both are scraped and neither is shown, which is the safe default and also a visible gap — an event
page with no description and no image is thin next to the venue's own listing.

**Two questions, one of them technical:**

1. **Copyright and licensing** — per source, which is what the licence-status field is for
2. **Traffic to small sites** — the guiding principle is *aggregate and link back, don't republish*,
   and a full description removes the reason to click through

**If images are shown**, a third question: store, cache or proxy them versus hotlink versus omit.
Hotlinking sends visitor requests to the venue's server and leaks the visitor's IP there, which is a
privacy-notice matter, not just an etiquette one.
