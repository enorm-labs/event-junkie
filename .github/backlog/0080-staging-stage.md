---
slug: staging-stage
title: A non-public test/staging stage, separate from production
type: Task
milestone: v0.2 — Deployable
labels: ["area:infra", "size:M", "blocked"]
priority: P0
status: Blocked
blocked-by: [terraform-iac]
---

ADR-012 treats the cost of a staging environment as a first-class selection criterion and budgets
roughly €7/month for it on Hetzner. A platform that makes staging expensive is a platform that
quietly deletes staging.

Several other issues are waiting on this existing at all: the scheduled k6 runs have nowhere to
point until there is a staging origin, and the per-environment `robots.txt` problem only becomes
solvable once a second environment exists to solve it for.

**Done when**

- [ ] A staging environment runs the same chart as production
- [ ] It is not publicly reachable, or is behind authentication
- [ ] It has its own database, and no production data is copied into it without a decision on
      what that means for the privacy notice
