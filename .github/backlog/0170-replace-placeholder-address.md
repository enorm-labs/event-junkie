---
slug: replace-placeholder-address
title: Replace the placeholder postal address
type: Task
milestone: v0.3 — Launch-ready
labels: ["area:legal", "size:S", "blocked"]
priority: P0
status: Blocked
blocked-by: [register-domain]
---

The imprint currently carries a placeholder. The real address comes from a rented *ladungsfähige
Anschrift* (Postflex or equivalent) — ADR-012 covers the domain, not this.

**Three files change, and a flag changes with them in the same commit:**

- `events-frontend/src/lib/legal.ts`
- `CODE_OF_CONDUCT.md`
- `SECURITY.md`
- set `CONTACT_DETAILS_ARE_PROVISIONAL = false`

A unit test fails if the flag and the placeholder ever disagree. That test is what stops a false
address going live quietly, so **do not split this across two commits** — the safety property only
holds while they move together.
