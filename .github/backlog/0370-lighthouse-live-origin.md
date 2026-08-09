---
slug: lighthouse-live-origin
title: Run Lighthouse / PageSpeed against the live origin
type: Task
milestone: v1.0 — Go-live
labels: ["area:frontend", "area:seo", "size:S", "needs-deployment"]
priority: P1
status: Blocked
blocked-by: [deploy-to-cloud]
---

Against the **live origin**, not a dev server. Caching headers and compression are deployment
properties: a local run measures a configuration that will never serve a real user.

**Done when**

- [ ] Lighthouse run against production for a list page and a detail page, mobile and desktop
- [ ] Anything actionable filed as its own issue rather than fixed inline here
- [ ] The numbers recorded somewhere, so the next run has something to compare against
