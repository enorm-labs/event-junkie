---
slug: watch-indexing
title: Watch indexing, especially of detail pages
type: Task
milestone: v1.0 — Go-live
labels: ["area:seo", "size:S", "needs-deployment"]
priority: P2
status: Blocked
blocked-by: [google-search-console]
---

This is the **named trigger in [ADR-014 §Decision 4](../../docs/adr/ADR-014_RENDERING_STRATEGY.md)
for reopening full SSR**: detail pages indexed late or not at all is the evidence. Anything short of
that is anticipation, and ADR-014 exists partly to stop the project from acting on anticipation.

**Check a few weeks after launch, not on day one.** A brand-new site with no inbound links has low
crawl priority, so early slowness is expected and proves nothing. Reacting to it would mean
rebuilding the rendering strategy on the basis of a signal that was always going to look like that.

**Done when**

- [ ] Use the URL Inspection tool on one event page to see the rendered HTML Google actually holds
- [ ] Coverage for the detail routes reviewed against the list routes
- [ ] A recorded verdict: SSR reopened, or not, and why
