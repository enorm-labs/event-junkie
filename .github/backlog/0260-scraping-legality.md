---
slug: scraping-legality
title: Confirm the legality of scraping and displaying event data
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:legal", "size:M", "needs-decision"]
priority: P1
status: Backlog
---

The whole product rests on this, and it has never been confirmed by anyone qualified.

The design is already the defensible one — ADR-007's guiding principle is *aggregate and link back,
don't republish*: store only the structured fields needed, always link to the source, polite rate
limits, transparent User-Agent, off-peak scheduling. That is a good position to be reviewed from,
not a substitute for the review.

**Done when**

- [ ] A qualified opinion on scraping and displaying event listings in this form
- [ ] Whatever it says is reflected in `docs/EVENT_SCOPE.md` and ADR-007, including anything the
      project must stop doing
- [ ] The venue opt-out route is confirmed as adequate

**References** — `docs/adr/ADR-007_WEB_SCRAPING_STRATEGY.md`, `docs/LEGAL.md`
