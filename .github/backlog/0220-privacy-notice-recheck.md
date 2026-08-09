---
slug: privacy-notice-recheck
title: Re-check the privacy notice against what actually runs
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:legal", "size:M", "blocked"]
priority: P0
status: Blocked
blocked-by: [art-28-contracts, logging-decisions, deploy-to-cloud]
---

The notice was written against a *proposed* infrastructure. Once the real one exists, every claim
in it needs checking against the thing that is actually running.

**Done when**

- [ ] The real processors are named
- [ ] The transfer mechanism in force for Cloudflare replaces the placeholder sentence
- [ ] `INFRASTRUCTURE_IS_PROPOSED = false`
- [ ] `LAST_REVIEWED` bumped

The flag is the point: while it is `true` the site tells readers the description is provisional,
which is honest. Flipping it is a claim that the document is now accurate, so it should be the
*last* step, not a housekeeping edit made early.
